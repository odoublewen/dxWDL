/** Generate intermediate representation from a WDL namespace.
  */
package dxWDL

import IR.{CVar, LinkedVar, SArg}
import net.jcazevedo.moultingyaml._
import scala.util.{Failure, Success, Try}
import Utils.{DX_URL_PREFIX, isNativeDxType, nonInterpolation, trace}
import wdl4s.wdl._
import wdl4s.wdl.AstTools
import wdl4s.wdl.AstTools.EnhancedAstNode
import wdl4s.wdl.expression._
import wdl4s.parser.WdlParser.{Ast, Terminal}
import wdl4s.wdl.types._
import wdl4s.wdl.values._
import wdl4s.wdl.WdlExpression.AstForExpressions

case class CompilerIR(gWorkflowOutputs: Option[Seq[WorkflowOutput]],
                      destination: String,
                      instanceTypeDB: InstanceTypeDB,
                      cef: CompilerErrorFormatter,
                      reorg: Boolean,
                      verbose: Utils.Verbose) {
    class DynamicInstanceTypesException private(ex: Exception) extends RuntimeException(ex) {
        def this() = this(new RuntimeException("Runtime instance type calculation required"))
    }

    // Environment (scope) where a call is made
    type CallEnv = Map[String, LinkedVar]

    // generate a stage Id
    var stageNum = 0
    def genStageId() : Utils.DXWorkflowStage = {
        val retval = Utils.DXWorkflowStage(s"stage_${stageNum}")
        stageNum += 1
        retval
    }

    // Convert the environment to yaml, and then pretty
    // print it.
    def prettyPrint(env: CallEnv) : String = {
        val m : Map[YamlValue, YamlValue] = env.map{ case (key, lVar) =>
            YamlString(key) -> lVar.yaml
        }.toMap
        YamlObject(m).print()
    }

    // Lookup for variables like x, A.x, A.B.x. The difficulty
    // is that A.x is interpreted in WDL as member access.
    def lookupInEnv(env: CallEnv, expr: WdlExpression) : LinkedVar = {
        val fqn: String = expr.toWdlString
        env.get(fqn) match {
            case Some(x) => x
            case None =>
                val t: Terminal = AstTools.findTerminals(expr.ast).head
                throw new Exception(cef.missingVarRef(t))
        }
    }

    /** Create a stub for an applet. This is an empty task
      that includes the input and output definitions. It is used
      to allow linking to native DNAx applets (and workflows in the future).

      For example, the stub for the Add task:
task Add {
    Int a
    Int b
    command { }
    output {
        Int result = a + b
    }
}

      is:

task Add {
    Int a
    Int b

    output {
        Int result
    }
*/
    def genAppletStub(applet: IR.Applet, scope: Scope) : WdlTask = {
        val task = WdlRewrite.taskGenEmpty(applet.name, Map.empty, scope)
        val inputs = applet.inputs.map{ cVar =>
            WdlRewrite.declaration(cVar.wdlType, cVar.name, None)
        }.toVector
        val outputs = applet.outputs.map{ cVar =>
            WdlRewrite.taskOutput(cVar.name, cVar.wdlType, task)
        }.toVector
        task.children = inputs ++ outputs
        task
    }

    // Rename member accesses inside an expression, from the form A.x
    // to A_x. This is used inside an applet of WDL generated code.
    //
    // Here, we take a shortcut, and just replace strings, instead of
    // doing a recursive syntax analysis (see ValueEvaluator wdl4s
    // module).
    def exprRenameVars(expr: WdlExpression,
                       allVars: Vector[CVar]) : WdlExpression = {
        var sExpr: String = expr.toWdlString
        for (cVar <- allVars) {
            // A.x => A_x
            sExpr = sExpr.replaceAll(cVar.name, cVar.dxVarName)
        }
        WdlExpression.fromString(sExpr)
    }

    def callUniqueName(call : WdlCall) = {
        val nm = call.alias match {
            case Some(x) => x
            case None => Utils.taskOfCall(call).name
        }
        Utils.reservedAppletPrefixes.foreach{ prefix =>
            if (nm.startsWith(prefix))
                throw new Exception(cef.illegalCallName(call))
        }
        Utils.reservedSubstrings.foreach{ sb =>
            if (nm contains sb)
                throw new Exception(cef.illegalCallName(call))
        }
        nm
    }

    // Figure out which instance to use.
    //
    // Extract three fields from the task:
    // RAM, disk space, and number of cores. These are WDL expressions
    // that, in the general case, could be calculated only at runtime.
    // Currently, we support only constants. If a runtime expression is used,
    // we convert it to a moderatly high constant.
    def calcInstanceType(taskOpt: Option[WdlTask]) : IR.InstanceType = {
        def lookup(varName : String) : WdlValue = {
            throw new DynamicInstanceTypesException()
        }
        def evalAttr(task: WdlTask, attrName: String) : Option[WdlValue] = {
            task.runtimeAttributes.attrs.get(attrName) match {
                case None => None
                case Some(expr) =>
                    Some(expr.evaluate(lookup, NoFunctions).get)
            }
        }

        try {
            taskOpt match {
                case None =>
                    // A utility calculation, that requires minimal computing resources.
                    // For example, the top level of a scatter block. We use
                    // the default instance type, because one will probably be available,
                    // and it will probably be inexpensive.
                    IR.InstanceTypeDefault
                case Some(task) =>
                    val dxInstaceType = evalAttr(task, Utils.DX_INSTANCE_TYPE_ATTR)
                    val memory = evalAttr(task, "memory")
                    val diskSpace = evalAttr(task, "disks")
                    val cores = evalAttr(task, "cpu")
                    IR.InstanceTypeConst(
                        instanceTypeDB.apply(dxInstaceType, memory, diskSpace, cores))
            }
        } catch {
            case e : DynamicInstanceTypesException =>
                // The generated code will need to calculate the instance type at runtime
                IR.InstanceTypeRuntime
        }
    }

    // split a block of statements into sub-blocks with continguous declarations.
    // Scatters are special, because they can also hold declarations that come
    // before them
    // Example:
    //
    //   call x
    //   Int a
    //   Int b
    //   call y
    //   call z
    //   String buf
    //   scatter ssc
    //  =>
    //   [call x]
    //   [Int a, Int b]
    //   [call y]
    //   [call z]
    //   [String buf, scatter ssc]
    //
    sealed trait Block
    case class BlockDecl(decls: Vector[Declaration]) extends Block
    case class BlockIf(preDecls: Vector[Declaration], cond: If) extends Block
    case class BlockScatter(preDecls: Vector[Declaration], scatter: Scatter) extends Block
    case class BlockScope(scope: Scope) extends Block

    def splitIntoBlocks(children: Seq[Scope]) : Vector[Block] = {
        val (subBlocks, decls) =
            children.foldLeft((Vector.empty[Block], Vector.empty[Declaration])) {
                case ((subBlocks, decls), d:Declaration) =>
                    (subBlocks, decls :+ d)
                case ((subBlocks, decls), ssc:Scatter) =>
                    (subBlocks :+ BlockScatter(decls, ssc), Vector.empty)
                case ((subBlocks, decls), cond:If) =>
                    (subBlocks :+ BlockIf(decls, cond), Vector.empty)
                case ((subBlocks, decls), scope) =>
                    if (decls.isEmpty)
                        (subBlocks :+ BlockScope(scope),  Vector.empty)
                    else
                        (subBlocks :+ BlockDecl(decls) :+ BlockScope(scope), Vector.empty)
            }
        if (decls.isEmpty)
            subBlocks
        else
            subBlocks :+ BlockDecl(decls)
    }

    // Check if the environment has A.B.C, A.B, or A.
    private def trailSearch(env: CallEnv, ast: Ast) : Option[(String, LinkedVar)] = {
        env.get(WdlExpression.toString(ast)) match {
            case None if !ast.isMemberAccess =>
                None
            case None =>
                ast.getAttribute("lhs") match {
                    case lhs:Ast => trailSearch(env, lhs)
                    case _ => None
                }
            case Some(lVar) =>
                Some(WdlExpression.toString(ast), lVar)
        }
    }

    // Update a closure with all the variables required
    // for an expression. Ignore variable accesses outside the environment;
    // it is assumed that these are accesses to local variables.
    //
    // @param  closure   call closure
    // @param  env       mapping from fully qualified WDL name to a dxlink
    // @param  expr      expression as it appears in source WDL
    private def updateClosure(closure : CallEnv,
                              env : CallEnv,
                              expr : WdlExpression) : CallEnv = {
        expr.ast match {
            case t: Terminal =>
                val srcStr = t.getSourceString
                t.getTerminalStr match {
                    case "identifier" =>
                        env.get(srcStr) match {
                            case Some(lVar) =>
                                val fqn = WdlExpression.toString(t)
                                closure + (fqn -> lVar)
                            case None => closure
                        }
                    case _ => closure
                }

            case a: Ast if a.isMemberAccess =>
                // This is a case of accessing something like A.B.C.
                trailSearch(env, a) match {
                    case Some((varName, lVar)) =>
                        closure + (varName -> lVar)
                    case None =>
                        // The variable is declared locally, it is not
                        // passed from the outside.
                        closure
                }

            case a: Ast =>
                // Figure out which variables are needed to calculate this expression,
                // and add bindings for them
                val memberAccesses = a.findTopLevelMemberAccesses().map(
                    // This is an expression like A.B.C
                    varRef => WdlExpression.toString(varRef)
                )
                val variables = AstTools.findVariableReferences(a).map{
                    varRef => varRef.terminal.getSourceString
                }
                val allDeps = (memberAccesses ++ variables).map{ fqn =>
                    env.get(fqn) match {
                        case Some(lVar) => Some(fqn -> lVar)
                        // There are cases where
                        // [findVariableReferences] gives us previous
                        // call names. We still don't know how to get rid of those cases.
                        case None => None
                    }
                }.flatten
                closure ++ allDeps
        }
    }

    // Make sure that the WDL code we generate is actually legal.
    private def verifyWdlCodeIsLegal(ns: WdlNamespace) : Unit = {
        // convert to a string
        val wdlCode = WdlPrettyPrinter(false, None).apply(ns, 0).mkString("\n")
        val nsTest:Try[WdlNamespace] = WdlNamespace.loadUsingSource(wdlCode, None, None)
        nsTest match {
            case Success(_) => ()
            case Failure(f) =>
                System.err.println("Error verifying generated WDL code")
                System.err.println(wdlCode)
                throw f
        }
    }

    // Print a WDL workflow that evaluates expressions
    def genEvalWorkflowFromDeclarations(name: String,
                                        declarations_i: Seq[Declaration],
                                        outputs: Vector[CVar]) : WdlWorkflow = {
        val declarations =
            if (declarations_i.isEmpty) {
                // Corner case: there are no inputs and no
                // expressions to calculate. Generated a valid
                // workflow that does nothing.
                val d = WdlRewrite.declaration(WdlIntegerType,
                                               "xxxx",
                                               Some(WdlExpression.fromString("0")))
                Vector(d)
            } else {
                declarations_i.toVector
            }
        val wf = WdlRewrite.workflowGenEmpty("w")
        val wfOutputs = outputs.map(
            cVar => WdlRewrite.workflowOutput(cVar.name, cVar.wdlType, wf))
        wf.children = declarations ++ wfOutputs
        wf
    }

    /**
      Create an applet+stage to evaluate expressions in the middle of
      a workflow.  Sometimes, we need to pass arguments to the
      applet. For example, the declaration xtmp2 below requires
      passing in the result of the Add call.

workflow w {
    Int ai
    call Add  {
        input: a=ai, b=3
    }
    Int xtmp2 = Add.result + 10
    call Multiply  {
        input: a=xtmp2, b=2
    }
}
      */
    def compileEvalAndPassClosure(appletName: String,
                                  declarations: Seq[Declaration],
                                  env: CallEnv) : (IR.Stage, IR.Applet) = {
        trace(verbose.on, s"Compiling evaluation applet ${appletName}")

        // Figure out the closure
        var closure = Map.empty[String, LinkedVar]
        declarations.foreach { decl =>
            decl.expression match {
                case Some(expr) =>
                    closure = updateClosure(closure, env, expr)
                case None => ()
            }
        }

        // figure out the inputs
        closure = closure.map{ case (key,lVar) =>
            val cVar = CVar(key, lVar.cVar.wdlType, DeclAttrs.empty, lVar.cVar.ast)
            key -> LinkedVar(cVar, lVar.sArg)
        }.toMap
        val inputVars: Vector[CVar] = closure.map{ case (_, lVar) => lVar.cVar }.toVector
        val inputDecls: Vector[Declaration] = closure.map{ case(_, lVar) =>
            WdlRewrite.declaration(lVar.cVar.wdlType, lVar.cVar.dxVarName, None)
        }.toVector

        // figure out the outputs
        val outputVars: Vector[CVar] = declarations.map{ decl =>
            CVar(decl.unqualifiedName, decl.wdlType, DeclAttrs.empty, decl.ast)
        }.toVector
        val outputDeclarations = declarations.map{ decl =>
            decl.expression match {
                case Some(expr) =>
                    WdlRewrite.declaration(decl.wdlType, decl.unqualifiedName,
                                           Some(exprRenameVars(expr, inputVars)))
                case None => decl
            }
        }.toVector
        val code:WdlWorkflow = genEvalWorkflowFromDeclarations(appletName,
                                                            inputDecls ++ outputDeclarations,
                                                            outputVars)

        // We need minimal compute resources, use the default instance type
        val applet = IR.Applet(appletName,
                               inputVars,
                               outputVars,
                               calcInstanceType(None),
                               IR.DockerImageNone,
                               destination,
                               IR.AppletKindEval,
                               WdlRewrite.namespace(code, Seq.empty))
        verifyWdlCodeIsLegal(applet.ns)

        // Link to the X.y original variables
        val inputs: Vector[SArg] = closure.map{ case (_, lVar) => lVar.sArg }.toVector

        (IR.Stage(appletName, genStageId(), appletName, inputs, outputVars),
         applet)
    }

    //  1) Assert that there are no calculations in the outputs
    //  2) Figure out from gWorkflowOutputs the output cVars and sArgs.
    //
    private def prepareOutputSection(env: CallEnv) : Vector[(CVar, SArg)] = {
        gWorkflowOutputs match {
            case None =>
                Vector.empty
            case Some(outputs) => outputs.map { wot =>
                val cVar = CVar(wot.unqualifiedName, wot.wdlType, DeclAttrs.empty, wot.ast)

                // we only want to deal with expressions that do
                // not require calculation.
                val expr = wot.requiredExpression
                val sArg = expr.ast match {
                    case t: Terminal =>
                        val srcStr = t.getSourceString
                        t.getTerminalStr match {
                            case "identifier" =>
                                env.get(srcStr) match {
                                    case Some(lVar) => lVar.sArg
                                    case None => throw new Exception(cef.missingVarRef(t))
                                }
                            case _ => throw new Exception(cef.missingVarRef(t))
                        }

                    case a: Ast if a.isMemberAccess =>
                        // This is a case of accessing something like A.B.C.
                        trailSearch(env, a) match {
                            case Some((_, lVar)) => lVar.sArg
                            case None => throw new Exception(cef.missingVarRef(a))
                        }

                    case a:Ast =>
                        throw new Exception(s"Not currently supported: expressions in output section (${expr.toWdlString})")
                        // Not clear what the problem is here
                        /*throw new Exception(cef.notCurrentlySupported(
                                                a,
                                                s"Expressions in output section (${expr.toWdlString})"))*/
                }

                (cVar, sArg)
            }.toVector
        }
    }

    // Check if the WDL expression is a constant. If so, return it,
    // otherwise, return None.
    private def evalIfIsWdlConst(expr: WdlExpression) : Option[WdlValue] = {
        expr.ast match {
            case t: Terminal if t.getTerminalStr == "identifier" =>
                None
            case t: Terminal if nonInterpolation(t) =>
                def lookup(x:String) : WdlValue = {
                    throw new Exception(cef.evaluatingTerminal(t, x))
                }
                val ve = ValueEvaluator(lookup, PureStandardLibraryFunctions)
                val wValue: WdlValue = ve.evaluate(expr.ast).get
                Some(wValue)
            case _ =>
                None
        }
    }

    // Check if a task is a real WDL task, or if it is a wrapper for a
    // native applet.

    // Compile a WDL task into an applet
    def compileTask(task : WdlTask) : (IR.Applet, Vector[CVar]) = {
        trace(verbose.on, s"Compiling task ${task.name}")

        // The task inputs are declarations that:
        // 1) are unassigned (do not have an expression)
        // 2) OR, are assigned, but optional
        //
        // According to the WDL specification, in fact, all task declarations
        // are potential inputs. However, that does not make that much sense.
        val inputVars : Vector[CVar] =  task.declarations.map{ decl =>
            if (Utils.declarationIsInput(decl))
                Some(CVar(decl.unqualifiedName,
                             decl.wdlType,
                             DeclAttrs.get(task, decl.unqualifiedName, cef),
                             decl.ast))
            else
                None
        }.flatten.toVector
        val outputVars : Vector[CVar] = task.outputs.map{ tso =>
            CVar(tso.unqualifiedName, tso.wdlType, DeclAttrs.empty, tso.ast)
        }.toVector

        // Figure out if we need to use docker
        val docker = task.runtimeAttributes.attrs.get("docker") match {
            case None =>
                IR.DockerImageNone
            case Some(expr) =>
                evalIfIsWdlConst(expr) match {
                    case Some(WdlString(url)) if url.startsWith(DX_URL_PREFIX) =>
                        // A constant image specified with a DX URL
                        val dxRecord = DxPath.lookupDxURLRecord(url)
                        IR.DockerImageDxAsset(dxRecord)
                    case _ =>
                        // Image will be downloaded from the network
                        IR.DockerImageNetwork
                }
        }
        // The docker container is on the platform, we need to remove
        // the dxURLs in the runtime section, to avoid a runtime
        // lookup. For example:
        //
        //   dx://dxWDL_playground:/glnexus_internal  ->   dx://project-xxxx:record-yyyy
        val taskCleaned = docker match {
            case IR.DockerImageDxAsset(dxRecord) =>
                WdlRewrite.taskReplaceDockerValue(task, dxRecord)
            case _ => task
        }
        val kind =
            (task.meta.get("type"), task.meta.get("id")) match {
                case (Some("native"), Some(id)) =>
                    // wrapper for a native applet
                    IR.AppletKindNative(id)
                case (_,_) =>
                    // a WDL task
                    IR.AppletKindTask
            }
        val applet = IR.Applet(task.name,
                               inputVars,
                               outputVars,
                               calcInstanceType(Some(task)),
                               docker,
                               destination,
                               kind,
                               WdlRewrite.namespace(taskCleaned))
        verifyWdlCodeIsLegal(applet.ns)
        (applet, outputVars)
    }

    def taskOfCall(call:WdlCall): WdlTask = {
        call match {
            case x:WdlTaskCall => x.task
            case x:WdlWorkflowCall =>
                throw new Exception(cef.notCurrentlySupported(call.ast, s"calling a workflow"))
        }
    }

    def findInputByName(call: WdlCall, cVar: CVar) : Option[(String,WdlExpression)] = {
        call.inputMappings.find{ case (k,v) => k == cVar.name }
    }

    def compileCall(call: WdlCall,
                    taskApplets: Map[String, (IR.Applet, Vector[CVar])],
                    env : CallEnv) : IR.Stage = {
        // Find the right applet
        val task = taskOfCall(call)
        val (callee, outputs) = taskApplets.get(task.name) match {
            case Some(x) => x
            case None => throw new Exception(s"Undefined task ${task.name}")
        }

        // Extract the input values/links from the environment
        val inputs: Vector[SArg] = callee.inputs.map{ cVar =>
            findInputByName(call, cVar) match {
                case None =>
                    // input is unbound; the workflow does not provide it.
                    if (Utils.isOptional(cVar.wdlType)) {
                        // This is an applet optional input, we don't have to supply it
                        IR.SArgEmpty
                    } else {
                        // A compulsory input. Print a warning, the user may wish to supply
                        // it at runtime.
                        val msg = s"""|Workflow doesn't supply required input ${cVar.name}
                                      |to call ${call.unqualifiedName}
                                      |""".stripMargin.replaceAll("\n", " ")
                        throw new Exception(cef.missingCallArgument(call.ast, msg))
                    }
                case Some((_,e)) => e.ast match {
                    case t: Terminal if t.getTerminalStr == "identifier" =>
                        val lVar = env.get(t.getSourceString) match {
                            case Some(x) => x
                            case None => throw new Exception(cef.missingVarRef(t))
                        }
                        lVar.sArg
                    case t: Terminal =>
                        def lookup(x:String) : WdlValue = {
                            throw new Exception(cef.evaluatingTerminal(t, x))
                        }
                        val ve = ValueEvaluator(lookup, PureStandardLibraryFunctions)
                        val wValue: WdlValue = ve.evaluate(e.ast).get
                        IR.SArgConst(wValue)
                    case a: Ast if a.isMemberAccess =>
                        // An expression like A.B.C, or A.x
                        val lVar = lookupInEnv(env, e)
                        lVar.sArg
                    case _:Ast =>
                        throw new Exception(cef.expressionMustBeConstOrVar(e))
                }
            }
        }

        val stageName = callUniqueName(call)
        IR.Stage(stageName, genStageId(), task.name, inputs, callee.outputs)
    }

    // Split a block (Scatter, If, ..) into the top declarations,
    // the and the bottom calls.
    def blockSplit(children: Vector[Scope]) : (Vector[Declaration], Vector[WdlCall]) = {
        val (topDecls, rest) = Utils.splitBlockDeclarations(children.toList)
        val calls : Seq[WdlCall] = rest.map {
            case call: WdlCall => call
            case decl:Declaration =>
                throw new Exception(cef.notCurrentlySupported(decl.ast,
                                                              "declaration in the middle of a block"))
            case x =>
                throw new Exception(cef.notCurrentlySupported(x.ast, "block element"))
        }
        (topDecls.toVector ,calls.toVector)
    }

    // Figure out the closure for a block, and then build the input
    // definitions.
    //
    //  preDecls: Declarations that come immediately before the block.
    //            We pack them into the same applet.
    //  topBlockExpr: condition variable, scatter loop expression, ...
    //  topDecls: declarations inside the block, that come at the beginning
    //  env: environment outside the block
    def blockInputs(preDecls: Vector[Declaration],
                    topBlockExpr: WdlExpression,
                    topDecls: Vector[Declaration],
                    calls: Vector[WdlCall],
                    env : CallEnv) : (Map[String, LinkedVar], Vector[CVar]) = {
        var closure = Map.empty[String, LinkedVar]
        preDecls.foreach { decl =>
            decl.expression match {
                case Some(expr) =>
                    closure = updateClosure(closure, env, expr)
                case None => ()
            }
        }

        // Ensure the top variable is in the closure.
        closure = updateClosure(closure, env, topBlockExpr)

        // Get closure dependencies from the top declarations
        topDecls.foreach { decl =>
            decl.expression match {
                case Some(expr) =>
                    closure = updateClosure(closure, env, expr)
                case None => ()
            }
        }
        // Make a pass on the calls inside the block
        calls.foreach { call =>
            call.inputMappings.foreach { case (_, expr) =>
                closure = updateClosure(closure, env, expr)
            }
        }

        val inputVars: Vector[CVar] = closure.map {
            case (varName, LinkedVar(cVar, _)) =>
                // a variable that must be passed to the scatter applet
                assert(env contains varName)
                Some(CVar(varName, cVar.wdlType, DeclAttrs.empty, cVar.ast))
        }.flatten.toVector

        (closure, inputVars)
    }

    // figure out if a variable is used only inside
    // the block (scatter, if, ...)
    def isLocal(decl: Declaration): Boolean = {
        if (Utils.isGeneratedVar(decl.unqualifiedName)) {
            // A variable generated by the compiler. It might be used only
            // locally.
            // find all dependent nodes
            val dNodes:Set[WdlGraphNode] = decl.downstream
            val declParent:Scope = decl.parent.get

            // figure out if these downstream nodes are in the same scope.
            val dnScopes:Set[WdlGraphNode] = dNodes.filter{ node =>
                node.parent.get.fullyQualifiedName != declParent.fullyQualifiedName
            }
            dnScopes.isEmpty
        } else {
            // A variable defined by the user, most likely used
            // somewhere else in the workflow. Don't check.
            false
        }
    }

    // Construct block outputs (scatter, conditional, ...), these are
    // made up of several categories:
    // 1. All the preamble variables, these could potentially be accessed
    // outside the scatter block.
    // 2. Individual call outputs. Each applet output becomes an array of
    // that type. For example, an Int becomes an Array[Int].
    // 3. Variables defined in the scatter block.
    //
    // Notes:
    // - exclude variables used only inside the block
    // - The type outside a block is *different* than the type in the block.
    //   For example, 'Int x' declared inside a scatter, is
    //   'Array[Int] x]' outside the scatter.
    def blockOutputs(preDecls: Vector[Declaration],
                     scope: Scope,
                     children : Seq[Scope]) : Vector[CVar] = {
        def outsideType(t: WdlType) : WdlType = {
            scope match {
                case _:Scatter => WdlArrayType(t)
                case _:If => t match {
                    // If the type is already optional, don't make it
                    // double optional.
                    case WdlOptionalType(_) => t
                    case _ => WdlOptionalType(t)
                }
                case _ => t
            }
        }
        val preVars: Vector[CVar] = preDecls
            .map( decl => CVar(decl.unqualifiedName, decl.wdlType, DeclAttrs.empty, decl.ast) )
            .toVector
        val outputVars : Vector[CVar] = children.map {
            case call:WdlTaskCall =>
                val task = taskOfCall(call)
                task.outputs.map { tso =>
                    val varName = callUniqueName(call) ++ "." ++ tso.unqualifiedName
                    CVar(varName, outsideType(tso.wdlType), DeclAttrs.empty, tso.ast)
                }
            case decl:Declaration if !isLocal(decl) =>
                Vector(CVar(decl.unqualifiedName, outsideType(decl.wdlType),
                               DeclAttrs.empty, decl.ast))
            case decl:Declaration =>
                // local variables, do not export
                Vector()
            case x =>
                throw new Exception(cef.notCurrentlySupported(
                                        x.ast, s"Unimplemented scatter element"))
        }.flatten.toVector
        preVars ++ outputVars
    }

    // Modify all the expressions used inside a block
    def blockTransform(preDecls: Vector[Declaration],
                       scope: Scope,
                       inputVars: Vector[CVar]) : (Vector[Declaration], Scope) = {
        // Rename the variables we got from the input.
        def transform(expr: WdlExpression) : WdlExpression = {
            exprRenameVars(expr, inputVars)
        }

        // transform preamble declarations
        val trPreDecls: Vector[Declaration] = preDecls.map { decl =>
            WdlRewrite.declaration(decl.wdlType, decl.unqualifiedName,
                                   decl.expression.map(transform))
        }

        // transform the expressions in a scatter
        def transformChild(scope: Scope): Scope = {
            scope match {
                case tc:WdlTaskCall =>
                    val inputs = tc.inputMappings.map{ case (k,expr) => (k, transform(expr)) }.toMap
                    WdlRewrite.taskCall(tc, inputs)
                case d:Declaration =>
                    new Declaration(d.wdlType, d.unqualifiedName,
                                    d.expression.map(transform), d.parent, d.ast)
                case _ => throw new Exception("Unimplemented scatter element")
            }
        }

        val trScope = scope match {
            case ssc:Scatter =>
                val children = ssc.children.map(transformChild(_))
                WdlRewrite.scatter(ssc, children, transform(ssc.collection))
            case cond:If =>
                val children = cond.children.map(transformChild(_))
                WdlRewrite.cond(cond, children, transform(cond.condition))
            case x =>
                throw new Exception(cef.notCurrentlySupported(x.ast, "block class"))
        }
        (trPreDecls, trScope)
    }

    // Create a valid WDL workflow that runs a block (Scatter, If,
    // etc.) The main modification is renaming variables of the
    // form A.x to A_x.
    def blockGenWorklow(preDecls: Vector[Declaration],
                        scope: Scope,
                        taskApplets: Map[String, (IR.Applet, Vector[CVar])],
                        inputVars: Vector[CVar],
                        outputVars: Vector[CVar]) : WdlNamespace = {
        // A workflow must have definitions for all the tasks it
        // calls. However, a scatter calls tasks, that are missing from
        // the WDL file we generate. To ameliorate this, we add stubs
        // for called tasks.
        val calls: Vector[WdlCall] = scope.calls.toVector
        val taskStubs: Map[String, WdlTask] =
            calls.foldLeft(Map.empty[String,WdlTask]) { case (accu, call) =>
                val name = call match {
                    case x:WdlTaskCall => x.task.name
                    case x:WdlWorkflowCall =>
                        throw new Exception(cef.notCurrentlySupported(x.ast, "calling workflows"))
                }
                val (irApplet,_) = taskApplets.get(name) match {
                    case None => throw new Exception(s"Calling undefined task ${name}")
                    case Some(x) => x
                }
                if (accu contains irApplet.name) {
                    // we have already created a stub for this call
                    accu
                } else {
                    // no existing stub, create it
                    val task = genAppletStub(irApplet, scope)
                    accu + (name -> task)
                }
            }
        val (trPreDecls, trScope) = blockTransform(preDecls, scope, inputVars)
        val decls: Vector[Declaration]  = inputVars.map{ cVar =>
            WdlRewrite.declaration(cVar.wdlType, cVar.dxVarName, None)
        }

        // Create new workflow that includes only this block
        val wf = WdlRewrite.workflowGenEmpty("w")
        wf.children = decls ++ trPreDecls :+ trScope
        val tasks = taskStubs.map{ case (_,x) => x}.toVector
        // namespace that includes the task stubs, and the workflow
        WdlRewrite.namespace(wf, tasks)
    }


    // Compile a scatter block. This includes the block of declarations that
    // come before it [preDecls]. Since we are creating a special applet for this, we might as
    // well evaluate those expressions as well.
    //
    // Note: the front end pass ensures that the scatter collection is a variable.
    def compileScatter(wfUnqualifiedName : String,
                       stageName: String,
                       preDecls: Vector[Declaration],
                       scatter: Scatter,
                       taskApplets: Map[String, (IR.Applet, Vector[CVar])],
                       env : CallEnv) : (IR.Stage, IR.Applet) = {
        trace(verbose.on, s"compiling scatter ${stageName}")
        val (topDecls, calls) = blockSplit(scatter.children.toVector)

        // Figure out the input definitions
        val (closure, inputVars) = blockInputs(preDecls,
                                               scatter.collection,
                                               topDecls,
                                               calls,
                                               env)

        val outputVars = blockOutputs(preDecls, scatter, scatter.children)
        val wdlCode = blockGenWorklow(preDecls, scatter, taskApplets, inputVars, outputVars)
        val callDict = calls.map(c => c.unqualifiedName -> Utils.taskOfCall(c).name).toMap

        // If any of the return types is non native, we need a collect subjob.
        val allNative = outputVars.forall(cVar => isNativeDxType(cVar.wdlType))
        val aKind =
            if (allNative) IR.AppletKindScatter(callDict)
            else IR.AppletKindScatterCollect(callDict)
        val applet = IR.Applet(wfUnqualifiedName ++ "_" ++ stageName,
                               inputVars,
                               outputVars,
                               calcInstanceType(None),
                               IR.DockerImageNone,
                               destination,
                               aKind,
                               wdlCode)
        verifyWdlCodeIsLegal(applet.ns)

        val sargs : Vector[SArg] = closure.map {
            case (_, LinkedVar(_, sArg)) => sArg
        }.toVector
        (IR.Stage(stageName, genStageId(), applet.name, sargs, outputVars),
         applet)
    }

    // Compile a conditional block.
    //
    // Note: the front end pass ensures that the if condition is a variable.
    def compileIf(wfUnqualifiedName : String,
                  stageName: String,
                  preDecls: Vector[Declaration],
                  cond: If,
                  taskApplets: Map[String, (IR.Applet, Vector[CVar])],
                  env : CallEnv) : (IR.Stage, IR.Applet) = {
        trace(verbose.on, s"compiling If block ${stageName}")
        val (topDecls, calls) = blockSplit(cond.children.toVector)

        // Figure out the input definitions
        val (closure, inputVars) = blockInputs(preDecls,
                                               cond.condition,
                                               topDecls,
                                               calls,
                                               env)

        val outputVars = blockOutputs(preDecls, cond, cond.children)
        val wdlCode = blockGenWorklow(preDecls, cond, taskApplets, inputVars, outputVars)
        val callDict = calls.map(c => c.unqualifiedName -> Utils.taskOfCall(c).name).toMap
        val applet = IR.Applet(wfUnqualifiedName ++ "_" ++ stageName,
                               inputVars,
                               outputVars,
                               calcInstanceType(None),
                               IR.DockerImageNone,
                               destination,
                               IR.AppletKindIf(callDict),
                               wdlCode)
        verifyWdlCodeIsLegal(applet.ns)

        val sargs : Vector[SArg] = closure.map {
            case (_, LinkedVar(_, sArg)) => sArg
        }.toVector
        (IR.Stage(stageName, genStageId(), applet.name, sargs, outputVars),
         applet)
    }

    // Create an applet to reorganize the output files. We want
    // to move the intermediate results to a subdirectory.
    //
    // Tricky parts:
    // 1) It needs to process all the workflow outputs, to find the files
    //    that belong to the final results.
    // 2) The workflow must wait for the reorg to complete. To achieve
    //    this, we return the inputs as outputs. The workflow as a whole
    //    waits on the reorg applet outputs.
    private def createReorgApplet(wfUnqualifiedName: String,
                                  wfOutputs: Vector[(CVar, SArg)]) : (IR.Stage, IR.Applet) = {
        val appletName = wfUnqualifiedName ++ "_reorg"
        trace(verbose.on, s"Compiling output reorganization applet ${appletName}")

        val inputVars: Vector[IR.CVar] = wfOutputs.map{ case (cVar, _) => cVar }
        val outputVars: Vector[IR.CVar] = inputVars
        val inputDecls: Vector[Declaration] = wfOutputs.map{ case(cVar, _) =>
            WdlRewrite.declaration(cVar.wdlType, cVar.dxVarName, None)
        }.toVector

        // The outputs are equal to the inputs
        val outputDecls: Vector[WorkflowOutput] = wfOutputs.map{ case (cVar, _) =>
            WdlRewrite.workflowOutput(cVar.dxVarName,
                                      cVar.wdlType,
                                      WdlExpression.fromString(cVar.dxVarName))
        }

        // Create a workflow with no calls.
        val code:WdlWorkflow = WdlRewrite.workflowGenEmpty("w")
        code.children = inputDecls ++ outputDecls

        // We need minimal compute resources, use the default instance type
        val applet = IR.Applet(appletName,
                               inputVars,
                               outputVars,
                               calcInstanceType(None),
                               IR.DockerImageNone,
                               destination,
                               IR.AppletKindWorkflowOutputReorg,
                               WdlRewrite.namespace(code, Seq.empty))
        verifyWdlCodeIsLegal(applet.ns)

        // Link to the X.y original variables
        val inputs: Vector[IR.SArg] = wfOutputs.map{ case (_, sArg) => sArg }.toVector

        (IR.Stage(appletName, genStageId(), appletName, inputs, outputVars),
         applet)
    }

    // Compile a workflow, having compiled the independent tasks.
    def compileWorkflow(wf: WdlWorkflow,
                        taskApplets: Map[String, (IR.Applet, Vector[CVar])]) : IR.Namespace = {
        trace(verbose.on, "IR: compiling workflow")

        // Get rid of workflow output declarations
        val children = wf.children.filter(x => !x.isInstanceOf[WorkflowOutput])

        // Only a subset of the workflow declarations are considered inputs.
        // Limit the search to the top block of declarations. Those that come at the very
        // beginning of the workflow.
        val (topDeclBlock, wfProperBlocks) = Utils.splitBlockDeclarations(children.toList)
        val (wfInputDecls, topDeclNonInputs) = topDeclBlock.partition{
            case decl:Declaration =>
                Utils.declarationIsInput(decl) &&
                !Utils.isGeneratedVar(decl.unqualifiedName)
            case _ => false
        }
        val wfProper = topDeclNonInputs ++ wfProperBlocks

        // Represent the workflow inputs with CVars.
        // It is possible to provide a default value to a workflow input.
        // For example:
        // workflow w {
        //   Int? x = 3
        //   ...
        // }
        // We handle only the case where the default is a constant.
        val wfInputs: Vector[(CVar,SArg)] = wfInputDecls.map{
            case decl:Declaration =>
                val cVar = CVar(decl.unqualifiedName, decl.wdlType, DeclAttrs.empty, decl.ast)
                val sArg = decl.expression match {
                    case None =>
                        // a default value is not provided, this is a workflow input
                        IR.SArgWorkflowInput(cVar)
                    case Some(expr) =>
                        evalIfIsWdlConst(expr) match {
                            case Some(wdlConst) => IR.SArgConst(wdlConst)
                            case None => throw new Exception(cef.workflowInputDefaultMustBeConst(expr))
                        }
                }
                (cVar, sArg)
        }.toVector

        // Create a stage per call/scatter-block/declaration-block
        val subBlocks = splitIntoBlocks(wfProper)

        // An environment where variables are defined. Initialized
        // to workflow inputs.
        var env : CallEnv = wfInputs.map { case (cVar,sArg) =>
            cVar.name -> LinkedVar(cVar, sArg)
        }.toMap
        var evalAppletNum = 0
        var scatterNum = 0
        var condNum = 0

        val allStageInfo = subBlocks.foldLeft(Vector.empty[(IR.Stage, Option[IR.Applet])]) {
            (accu, child) =>
            val (stage, appletOpt) = child match {
                case BlockDecl(decls) =>
                    evalAppletNum += 1
                    val appletName = wf.unqualifiedName ++ "_eval" ++ evalAppletNum.toString
                    val (stage, applet) = compileEvalAndPassClosure(appletName, decls, env)
                    (stage, Some(applet))
                case BlockIf(preDecls, cond) =>
                    condNum += 1
                    val condName = Utils.IF ++ "_" ++ condNum.toString
                    val (stage, applet) = compileIf(wf.unqualifiedName, condName, preDecls,
                                                    cond, taskApplets, env)
                    (stage, Some(applet))
                case BlockScatter(preDecls, scatter) =>
                    scatterNum += 1
                    val scatterName = Utils.SCATTER ++ "_" ++ scatterNum.toString
                    val (stage, applet) = compileScatter(wf.unqualifiedName, scatterName, preDecls,
                                                         scatter, taskApplets, env)
                    (stage, Some(applet))
                case BlockScope(call: WdlCall) =>
                    val stage = compileCall(call, taskApplets, env)
                    (stage, None)
                case BlockScope(x) =>
                    throw new Exception(cef.notCurrentlySupported(
                                            x.ast, s"Workflow element type=${x}"))
            }

            // Add bindings for the output variables. This allows later calls to refer
            // to these results. In case of scatters, there is no block name to reference.
            for (cVar <- stage.outputs) {
                val fqVarName : String = child match {
                    case BlockDecl(decls) => cVar.name
                    case BlockIf(_, _) => cVar.name
                    case BlockScatter(_, _) => cVar.name
                    case BlockScope(call : WdlCall) => stage.name ++ "." ++ cVar.name
                    case _ => throw new Exception("Sanity")
                }
                env = env + (fqVarName ->
                                 LinkedVar(cVar, IR.SArgLink(stage.name, cVar)))
            }
            accu :+ (stage,appletOpt)
        }

        val wfOutputs = prepareOutputSection(env)

        // Add a reorganization applet if requested
        val allStageInfoWithReorg: Vector[(IR.Stage, Option[IR.Applet])] =
            if (reorg) {
                val (rStage, rApl) = createReorgApplet(wf.unqualifiedName, wfOutputs)
                allStageInfo :+ (rStage, Some(rApl))
            } else {
                allStageInfo
            }

        val (stages, auxApplets) = allStageInfoWithReorg.unzip
        val tApplets: Map[String, IR.Applet] =
            taskApplets.map{ case (k,(applet,_)) => k -> applet }.toMap
        val aApplets: Map[String, IR.Applet] =
            auxApplets
                .flatten
                .map(apl => apl.name -> apl).toMap

        val irwf = IR.Workflow(wf.unqualifiedName, wfInputs, wfOutputs, stages)
        IR.Namespace(Some(irwf), tApplets ++ aApplets)
    }

    // Load imported tasks
    def loadImportedTasks(ns: WdlNamespace) : Set[WdlTask] = {
        // Make a pass, and figure out what we access
        //
        ns.taskCalls.map{ call:WdlTaskCall =>
            val taskFqn = call.task.fullyQualifiedName
            ns.resolve(taskFqn) match {
                case Some(task:WdlTask) => task
                case x => throw new Exception(s"Resolved call to ${taskFqn} and got (${x})")
            }
        }.toSet
    }

    // compile the WDL source code into intermediate representation
    def apply(ns : WdlNamespace) : IR.Namespace = {
        trace(verbose.on, "IR pass")

        // Load all accessed applets, local or imported
        val accessedTasks: Set[WdlTask] = loadImportedTasks(ns)
        val accessedTaskNames = accessedTasks.map(task => task.name)
        trace(verbose.on, s"Accessed tasks = ${accessedTaskNames}")

        // Make sure all local tasks are included; we want to compile
        // them even if they are not accessed.
        val allTasks:Set[WdlTask] = accessedTasks ++ ns.tasks.toSet

        // compile all the tasks into applets
        trace(verbose.on, "compiling tasks into dx:applets")

        val taskApplets: Map[String, (IR.Applet, Vector[CVar])] = allTasks.map{ task =>
            val (applet, outputs) = compileTask(task)
            task.name -> (applet, outputs)
        }.toMap

        val irApplets: Map[String, IR.Applet] = taskApplets.map{
            case (key, (irApplet,_)) => key -> irApplet
        }.toMap

        ns match {
            case nswf : WdlNamespaceWithWorkflow =>
                val wf = nswf.workflow
                compileWorkflow(wf, taskApplets)
            case _ =>
                // The namespace contains only applets, there
                // is no workflow to compile.
                IR.Namespace(None, irApplets)
        }
    }
}
