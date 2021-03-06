package dxWDL.util

import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.Inside._
import wom.callable.CallableTaskDefinition

// These tests involve compilation -without- access to the platform.
//
class ParseWomSourceFileTest extends FlatSpec with Matchers {
    private def normalize(s: String) : String = {
        s.replaceAll("(?s)\\s+", " ").trim
    }

    it should "find task sources" in {
        val srcCode =
            """|task hello {
               |   Milo is selling the mess hall chairs!
               |}
               |""".stripMargin

        val taskDir = ParseWomSourceFile.scanForTasks(srcCode)
        taskDir.size should equal(1)
        val helloTask = taskDir.get("hello")
        inside (helloTask) {
            case Some(x) =>
                normalize(x) should equal(normalize(srcCode))
        }
    }

    it should "find task source in complex WDL task" in {
        val srcCode =
            """|task sub {
               |   Int a
               |   Int b
               |   command {
               |      ls -lR
               |   }
               |}
               |""".stripMargin

        val taskDir = ParseWomSourceFile.scanForTasks(srcCode)
        taskDir.size should equal(1)
        val subTask = taskDir.get("sub")
        inside (subTask) {
            case Some(x) =>
                normalize(x) should equal(normalize(srcCode))
        }
    }

    it should "find sources in a script with two tasks" in {
        val srcCode =
            """|task sub {
               |   Int a
               |   Int b
               |   command {
               |      ls -lR
               |   }
               |}
               |
               |task major {
               |   major major is up on the tree
               |   { }
               |}
               |""".stripMargin

        val taskDir = ParseWomSourceFile.scanForTasks(srcCode)
        taskDir.size should equal(2)
        inside (taskDir.get("sub")) {
            case Some(x) =>
                x should include ("ls -lR")
        }
        inside (taskDir.get("major")) {
            case Some(x) =>
                x should include ("tree")
                x should include ("{ }")
        }
    }

    it should "find source task in a WDL 1.0 script" in {
        val srcCode =
            """|version 1.0
               |
               |task Add {
               |    input {
               |        Int a
               |        Int b
               |    }
               |    command {
               |        echo $((${a} + ${b}))
               |    }
               |    output {
               |        Int result = read_int(stdout())
               |    }
               |}
               |""".stripMargin

        val taskDir = ParseWomSourceFile.scanForTasks(srcCode)
        taskDir.size should equal(1)
        inside (taskDir.get("Add")) {
            case Some(x) =>
                x should include ("echo $((${a} + ${b}))")
        }
    }

    it should "parse the meta section in wdl draft2" in {
        val srcCode =
            """|task native_sum_012 {
               |  Int? a
               |  Int? b
               |  command {}
               |  output {
               |    Int result = 0
               |  }
               |  meta {
               |     type : "native"
               |     id : "applet-xxxx"
               |  }
               |}
               |
               |""".stripMargin

        val (task : CallableTaskDefinition, _) = ParseWomSourceFile.parseWdlTask(srcCode)
        task.meta shouldBe (Map("type" -> "native",
                                "id" -> "applet-xxxx"))
    }

    it should "parse the meta section in wdl 1.0" in {
        val srcCode =
            """|version 1.0
               |
               |task native_sum_012 {
               |  input {
               |    Int? a
               |    Int? b
               |  }
               |  command {}
               |  output {
               |    Int result = 0
               |  }
               |  meta {
               |     type : "native"
               |     id : "applet-xxxx"
               |  }
               |}
               |
               |""".stripMargin

        val (task : CallableTaskDefinition, _) = ParseWomSourceFile.parseWdlTask(srcCode)
        task.meta shouldBe (Map("type" -> "native",
                                "id" -> "applet-xxxx"))
    }

}
