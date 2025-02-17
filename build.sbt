import scala.scalanative.build.GC
import coursierapi.ResolutionParams
import coursierapi.Repository
import sbt.io.Using
import java.util.stream.Collectors
import java.nio.file.Files
import scala.scalanative.build.Mode
import scala.scalanative.build.NativeConfig
import scala.scalanative.build.LTO
import commandmatrix.extra.*
import com.indoorvivants.detective.Platform
import Platform.*

import _root_.bindgen.interface.*
import ArtifactNames.*

import java.nio.file.Paths

lazy val Versions = new {
  val decline = "2.4.1"
  val scalaNative = nativeVersion
  val junit = "0.13.3"
  val scalameta = "4.5.13"
  val b2s = "0.3.17"
  val pluginTargetSN = "0.4.9"
  val pluginTargetSBT = "1.6.1"
  val detective = "0.0.2"

  val Scala3 = "3.2.1"
  val Scala212 = "2.12.17"
  val Scala213 = "2.13.10"
  val Scala2 = List(Scala212, Scala213)

}

inThisBuild(
  Seq(
    organization := "com.indoorvivants",
    organizationName := "Anton Sviridov",
    homepage := Some(url("https://github.com/indoorvivants/sn-bindgen")),
    startYear := Some(2022),
    licenses := List(
      "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
    ),
    developers := List(
      Developer(
        "keynmol",
        "Anton Sviridov",
        "velvetbaldmime@protonmail.com",
        url("https://blog.indoorvivants.com")
      )
    )
  )
)

// --------------MODULES-------------------------
lazy val root = project
  .in(file("."))
  .aggregate(bindgen, libclang)
  .aggregate(iface.projectRefs*)
  .aggregate(plugin.projectRefs*)
  .aggregate(tests.projectRefs*)
  .settings(
    publish / skip := true,
    publishLocal / skip := true
  )

lazy val iface = projectMatrix
  .in(file("modules/interface"))
  .someVariations(
    Versions.Scala2 :+ Versions.Scala3,
    List(
      VirtualAxis.jvm,
      VirtualAxis.native
    )
  )(
    MatrixAction.ForScala(_.isScala2).Settings(scalacOptions += "-Xsource:3"),
    MatrixAction
      .ForPlatform(VirtualAxis.native)
      .Configure(_.enablePlugins(ScalaNativeJUnitPlugin)),
    MatrixAction
      .ForPlatform(VirtualAxis.jvm)
      .Settings(
        Seq(
          Test / fork := true,
          libraryDependencies += "com.github.sbt" % "junit-interface" % Versions.junit % Test
        )
      )
  )
  .settings(
    moduleName := "bindgen-interface",
    libraryDependencies += "com.indoorvivants.detective" %%% "platform" % Versions.detective
  )
  .enablePlugins(BuildInfoPlugin)
  .settings(
    buildInfoPackage := "bindgen.interface",
    buildInfoKeys := Seq[BuildInfoKey](
      version,
      scalaVersion,
      scalaBinaryVersion
    )
  )
  .settings(noTests)

lazy val bindgen = project
  .in(file("modules/bindgen"))
  .dependsOn(libclang)
  .enablePlugins(ScalaNativePlugin, ScalaNativeJUnitPlugin, BuildInfoPlugin)
  .settings(nativeCommon)
  .settings(noTests)
  .settings(Compile / nativeConfig ~= environmentConfiguration)
  .settings(nativeConfig ~= usesLibClang)
  .settings(
    buildInfoPackage := "bindgen",
    buildInfoKeys := Seq[BuildInfoKey](
      version,
      scalaVersion,
      scalaBinaryVersion,
      BuildInfoKey("nativeVersion" -> nativeVersion)
    )
  )
  .settings(
    moduleName := "bindgen",
    libraryDependencies += "com.indoorvivants.detective" %%% "platform" % Versions.detective,
    libraryDependencies += ("com.monovore" %%% "decline" % Versions.decline cross CrossVersion.for3Use2_13)
      .excludeAll(ExclusionRule("org.scala-native")),
    libraryDependencies += compilerPlugin(
      "org.polyvariant" % "better-tostring" % Versions.b2s cross CrossVersion.full
    ),
    testOptions += Tests.Argument(TestFrameworks.JUnit, "-a", "-s", "-v")
  )
  .settings {
    val detected = detectBinaryArtifacts
    detected
      .map { case (_, (artifact, file)) =>
        addArtifact(Def.setting(artifact), Def.task(file))
      }
      .toSeq
      .flatten
  }

lazy val binaryArtifacts = project
  .in(file("build/binary-artifacts"))
  .enablePlugins(ScalaNativePlugin)
  .settings(nativeCommon)
  .settings(noTests)
  .settings(
    packageBin / publishArtifact := false,
    packageDoc / publishArtifact := false,
    packageSrc / publishArtifact := false,
    moduleName := "bindgen"
  )
  .settings {
    val detected = detectBinaryArtifacts
    detected
      .map { case (_, (artifact, file)) =>
        addArtifact(Def.setting(artifact), Def.task(file))
      }
      .toSeq
      .flatten
  }

lazy val localBindgenArtifact = project
  .in(file("local-bindgen"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(bindgen, libclang)
  .settings(nativeCommon)
  .settings(
    packageBin / publishArtifact := false,
    packageDoc / publishArtifact := false,
    packageSrc / publishArtifact := false,
    moduleName := "bindgen"
  )
  .settings {

    def build(classifier: String) =
      Artifact("bindgen", classifier)
        .withExtension("jar")
        .withType("jar")
        .withConfigurations(Vector(Compile))
    addArtifact(
      Def.setting(build(jarString(Platform.target))),
      Def.task { (bindgen / Compile / nativeLink).value }
    )
  }

lazy val plugin = projectMatrix
  .in(file("modules/sbt-plugin"))
  .defaultAxes(VirtualAxis.scalaABIVersion(Versions.Scala212), VirtualAxis.jvm)
  .allVariations(List(Versions.Scala212), List(VirtualAxis.jvm))
  .dependsOn(iface)
  .settings(
    sbtPlugin := true,
    addSbtPlugin(
      "org.scala-native" % "sbt-scala-native" % Versions.pluginTargetSN
    ),
    pluginCrossBuild / sbtVersion := Versions.pluginTargetSBT,
    moduleName := "bindgen-sbt-plugin",
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++
        Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
    },
    scriptedBufferLog := false,
    publishLocal := publishLocal
      .dependsOn(
        localBindgenArtifact / publishLocal
      )
      .value
  )
  .enablePlugins(ScriptedPlugin, SbtPlugin)

lazy val libclang = project
  .in(file("modules/libclang"))
  .enablePlugins(ScalaNativePlugin)
  .settings(nativeCommon)
  .settings(nativeConfig ~= usesLibClang)
  .settings(
    moduleName := "bindgen-libclang",
    Compile / doc / scalacOptions ~= { opts =>
      opts.filterNot(_.contains("-Xplugin"))
    }
  )

lazy val tests = projectMatrix
  .in(file("modules/tests"))
  .dependsOn(iface)
  .someVariations(
    Versions.Scala2 :+ Versions.Scala3,
    List(
      VirtualAxis.jvm,
      VirtualAxis.native
    )
  )(
    MatrixAction((sv, axes) =>
      sv.isScala2 && axes.contains(VirtualAxis.native)
    ).Skip,
    MatrixAction.ForScala(_.isScala2).Settings(scalacOptions += "-Xsource:3"),
    MatrixAction
      .ForPlatform(VirtualAxis.native)
      .Configure(
        _.enablePlugins(ScalaNativeJUnitPlugin, BindgenPlugin)
          .settings(nativeCommon)
          .settings(Compile / nativeConfig ~= environmentConfiguration)
          .settings(
            Compile / bindgenBinary := (bindgen / Compile / nativeLink).value,
            Test / bindgenBinary := (bindgen / Compile / nativeLink).value,
            bindgenBindings := Seq.empty,
            bindgenBinary := (bindgen / Compile / nativeLink).value,
            Test / bindgenBindings := {
              val resourcesDirs = (Test / unmanagedResourceDirectories).value

              resourcesDirs.flatMap { resourceDir =>
                val headersPath = resourceDir / "scala-native"
                val files = headersPath.toGlob / "**" / "*.h"
                import scala.collection.JavaConverters.*
                val headerSpec = Files
                  .walk(headersPath.toPath, 1)
                  .collect(Collectors.toList())
                  .asScala
                  .filter(_.toFile().isFile())
                  .filter(_.toFile.ext == "h")
                  .map(h =>
                    h.toFile -> headersPath.toPath
                      .relativize(h)
                      .toString
                      .dropRight(2)
                  )
                  .toMap

                headerSpec.toSeq.map { case (header, name) =>
                  Binding(
                    header,
                    s"lib_test_$name",
                    cImports = List(s"$name.h"),
                    logLevel = LogLevel.Info
                  )
                }
              }
            }
          )
      ),
    MatrixAction
      .ForPlatform(VirtualAxis.jvm)
      .Settings(
        Seq(
          Test / fork := true,
          Test / envVars += "BINARY" -> (bindgen / Compile / nativeLink).value.toString,
          Test / envVars += "BINDGEN_CLANG_PATH" -> (bindgen / Compile / nativeClang).value.toString,
          libraryDependencies += "com.github.sbt" % "junit-interface" % Versions.junit % Test
        )
      )
  )
  .settings(
    publish / skip := true,
    publishLocal / skip := true,
    testOptions += Tests.Argument(TestFrameworks.JUnit, "-a", "-s", "-v"),
    Test / sources := {
      val defaults = (Test / sources).value
      if (Platform.os == Platform.OS.Windows)
        defaults.filterNot(_.toString.toLowerCase.contains("no-windows"))
      else defaults
    }
  )

lazy val scalaNativeLibParser = project
  .in(file("modules/scalaNativeLib"))
  .settings(scalaVersion := Versions.Scala213)
  .settings(
    libraryDependencies += "org.scalameta" %% "scalameta" % Versions.scalameta,
    scalacOptions += "-Xsource:3"
  )

lazy val docs =
  project
    .in(file("docs"))
    .enablePlugins(SubatomicPlugin)
    .settings(
      scalaVersion := "3.1.3",
      fork := true,
      publish / skip := true,
      Compile / run / envVars := Map(
        "BINDGEN_BINARY" -> (bindgen / Compile / nativeLink).value.toString()
      )
    )
// --------------HELPERS-------------------------

def detectBinaryArtifacts: Map[String, (Artifact, File)] = if (
  sys.env.contains("BINARIES")
) {
  val folder = new File(sys.env("BINARIES"))

  def build(classifier: String, file: File): (String, (Artifact, File)) = {
    val artif = Artifact("bindgen", classifier)
      .withExtension("jar")
      .withType("jar")
      .withConfigurations(Vector(Compile))

    classifier -> (artif, file)
  }

  import Platform.OS
  import Platform.Arch
  import Platform.Bits

  val artifacts = for {
    os <- Seq(OS.Windows, OS.Linux, OS.MacOS)
    arch <- Seq(Arch.Intel, Arch.Arm)
    bits <- Seq(Bits.x32, Bits.x64)
    target = Platform.Target(os, arch, bits)
    filename = os match {
      case OS.Windows => "bindgen.exe"
      case _          => "bindgen"
    }
    file = folder / s"sn-bindgen-${coursierString(target)}" / filename
    if file.exists()
  } yield build(jarString(target), file)

  artifacts.toMap
} else Map.empty

def environmentConfiguration(conf: NativeConfig): NativeConfig = {
  var modified = conf
  if (sys.env.contains("SN_RELEASE"))
    modified = modified.withMode(Mode.releaseFast)

  if (sys.env.contains("SN_SANITIZE")) {
    val opts =
      "-fsanitize=address -fno-omit-frame-pointer -fsanitize-address-use-after-return=always"
        .split(' ')
        .toList

    modified = modified
      .withLinkingOptions(modified.linkingOptions ++ opts)
      .withCompileOptions(modified.compileOptions ++ opts)
      .withOptimize(false)
      .withMode(Mode.debug)
      .withLTO(LTO.none)
  }

  if (sys.env.contains("SN_GC"))
    modified = modified.withGC(GC.apply(sys.env("SN_GC")))

  modified
}

def usesLibClang(conf: NativeConfig) = {
  val libraryName =
    if (Platform.os == Platform.OS.Windows) "libclang" else "clang"

  val detected = llvmFolder(conf.clang.toAbsolutePath())

  val arm64 =
    if (Platform.arch == Platform.Arch.Arm) Seq("-arch", "arm64") else Seq.empty

  conf
    .withLinkingOptions(
      conf.linkingOptions ++
        Seq("-l" + libraryName) ++
        detected.llvmLib.map("-L'" + _ + "'") ++ arm64
    )
    .withCompileOptions(
      conf.compileOptions ++ detected.llvmInclude.map("-I" + _) ++ arm64
    )
}

// --------------SETTINGS-------------------------

ThisBuild / resolvers += Resolver.sonatypeRepo("snapshots")

lazy val markdownDocuments = taskKey[Seq[java.nio.file.Path]]("")
markdownDocuments := {
  markdownDocuments.inputFiles
}

markdownDocuments / fileInputs ++=
  Seq(
    (docs / baseDirectory).value.toGlob / "**" / "*.md",
    (docs / baseDirectory).value.toGlob / "**" / "*.css",
    (
      docs / baseDirectory
    ).value.toGlob / "**" / "*.js"
  )

lazy val buildSite = inputKey[Unit]("")
buildSite := Def.inputTaskDyn {
  val defaultArgs =
    Seq("--destination", ((ThisBuild / baseDirectory).value / "_site").toString)
  val parsed = sbt.complete.DefaultParsers.spaceDelimited("<arg>").parsed

  val args = (defaultArgs ++ parsed).mkString(" ")

  val _ = markdownDocuments.value

  Def.taskDyn {
    (docs / Compile / runMain)
      .toTask(s" bindgen.docs.Docs build $args")
  }

}.evaluated

lazy val buildBinary = taskKey[File]("")

buildBinary := {
  val built = (bindgen / Compile / nativeLink).value
  val name =
    if (Platform.os == Platform.OS.Windows) "bindgen.exe" else "bindgen"
  val dest = (ThisBuild / baseDirectory).value / "bin" / name

  IO.copyFile(built, dest)

  dest
}

lazy val nativeCommon = Seq(
  scalaVersion := Versions.Scala3
)

lazy val noTests = Seq(
  test := {}
)

Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val versionDump =
  taskKey[Unit]("Dumps the version in a file named version")

versionDump := {
  val file = (ThisBuild / baseDirectory).value / "version"
  IO.write(file, (Compile / version).value)
}

addCommandAlias(
  "ci",
  "scalafmtCheckAll; scalafmtSbtCheck; test; plugin/scripted"
)

addCommandAlias(
  "devPublish",
  "publishLocal; localBindgenArtifact/publishLocal; show bindgen/version"
)

addCommandAlias("preCI", "scalafmtAll; scalafmtSbt;")

def llvmFolder(clangPath: java.nio.file.Path) = {
  import Platform.OS.*

  Platform.os match {
    case MacOS =>
      val detected =
        sys.env.get("LLVM_BIN").map(Paths.get(_)).map(_.getParent).toList

      val speculative = List(
        Paths.get("/usr/local/opt/llvm@14"),
        Paths.get("/usr/local/opt/llvm"),
        Paths.get("/opt/homebrew/opt/llvm@14"),
        Paths.get("/opt/homebrew/opt/llvm")
      )

      val all = (detected ++ speculative).dropWhile(!_.toFile.exists())

      val includes = all
        .map(_.resolve("include"))
        .map(_.toAbsolutePath().toString)

      val lib = all
        .map(_.resolve("lib"))
        .map(_.toAbsolutePath().toString)

      LLVMInfo(
        llvmInclude = includes,
        llvmLib = lib
      )
    case Linux | Windows =>
      // <llvm-path>/bin/clang
      val realPath = clangPath.toRealPath()
      val binFolder = realPath.getParent()
      val llvmFolder = binFolder.getParent()

      if (llvmFolder.toFile.exists())
        LLVMInfo(
          llvmInclude = List(llvmFolder.resolve("include").toString),
          llvmLib = List(llvmFolder.resolve("lib").toString)
        )
      else LLVMInfo(Nil, Nil)
    case _ => LLVMInfo(Nil, Nil)
  }
}
