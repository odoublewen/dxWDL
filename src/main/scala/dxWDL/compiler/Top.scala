// Interface to the compilation tool chain. The methods here are the only ones
// that should be called to perform compilation.

package dxWDL.compiler

import common.Checked
import common.validation.Validation._
import cromwell.core.path.{DefaultPathBuilder, Path}
import cromwell.languages.LanguageFactory
import cromwell.languages.util.ImportResolver._
import dxWDL.CompilerOptions
import languages.cwl.CwlV1_0LanguageFactory
import languages.wdl.draft2.WdlDraft2LanguageFactory
import languages.wdl.draft3.WdlDraft3LanguageFactory
import java.nio.file.{Files, Paths}
import scala.collection.JavaConverters._
import scala.util.Try
import wom.executable.WomBundle
import wom.expression.NoIoFunctionSet
import wom.graph._

object Top {

    def getBundle(mainFile: Path): Checked[WomBundle] = {
        // Resolves for:
        // - Where we run from
        // - Where the file is
        lazy val importResolvers = List(
            directoryResolver(DefaultPathBuilder.build(
                                                 Paths.get(".")),
                                             allowEscapingDirectory = true
            ),
            directoryResolver(
                DefaultPathBuilder.build(Paths.get(mainFile.toAbsolutePath.toFile.getParent)),
                allowEscapingDirectory = true
            ),
            httpResolver
        )

        readFile(mainFile.toAbsolutePath.pathAsString) flatMap { mainFileContents =>
            val languageFactory = if (mainFile.name.toLowerCase().endsWith("wdl")) {
                if (mainFileContents.startsWith("version 1.0") || mainFileContents.startsWith("version draft-3")) {
                    new WdlDraft3LanguageFactory(Map.empty)
                } else {
                    new WdlDraft2LanguageFactory(Map.empty)
                }
            } else new CwlV1_0LanguageFactory(Map.empty)

            languageFactory.getWomBundle(mainFileContents, "{}", importResolvers, List(languageFactory))
        }
    }

    private def readFile(filePath: String): Checked[String] =
        Try(Files.readAllLines(Paths.get(filePath)).asScala.mkString(System.lineSeparator())).toChecked


    // Compile IR only
    def applyOnlyIR(sourceFile: String,
                    cOpt: CompilerOptions) : IR.Bundle = {
        val src : Path = DefaultPathBuilder.build(Paths.get(sourceFile))
        val bundle : wom.executable.WomBundle = getBundle(src)

        // Compile the WDL workflow into an Intermediate
        // Representation (IR)
        GenerateIR.applyOnlyIR(bundle, cOpt.verbose)
    }
}
