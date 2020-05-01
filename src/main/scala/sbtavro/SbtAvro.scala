package sbtavro

import java.io.File
import java.util.concurrent.atomic.AtomicReference

import org.apache.avro.compiler.idl.Idl
import org.apache.avro.compiler.specific.SpecificCompiler
import org.apache.avro.compiler.specific.SpecificCompiler.FieldVisibility
import org.apache.avro.generic.GenericData.StringType
import org.apache.avro.{Protocol, Schema}
import sbt.Keys._
import sbt._
import Path.relativeTo
import com.spotify.avro.mojo.AvroFileRef
import sbt.librarymanagement.{ArtifactFilter, DependencyFilter, SubDepFilter}

/**
 * Simple plugin for generating the Java sources for Avro schemas and protocols.
 */
object SbtAvro extends AutoPlugin {

  val AvroClassifier = "avro"

  private val AvroAvrpFilter: NameFilter = "*.avpr"
  private val AvroAvdlFilter: NameFilter = "*.avdl"
  private val AvroAvscFilter: NameFilter = "*.avsc"
  private val AvroFilter: NameFilter = AvroAvscFilter | AvroAvdlFilter | AvroAvrpFilter

  private val JavaFileFilter: NameFilter = "*.java"

  object autoImport {

    import Defaults._

    // format: off
    val avroStringType = settingKey[String]("Type for representing strings. Possible values: CharSequence, String, Utf8. Default: CharSequence.")
    val avroEnableDecimalLogicalType = settingKey[Boolean]("Set to true to use java.math.BigDecimal instead of java.nio.ByteBuffer for logical type \"decimal\".")
    val avroFieldVisibility = settingKey[String]("Field visibility for the properties. Possible values: private, public, public_deprecated. Default: public_deprecated.")
    val avroUseNamespace = settingKey[Boolean]("Validate that directory layout reflects namespaces, i.e. src/main/avro/com/myorg/MyRecord.avsc.")
    val avroSource = settingKey[File]("Default Avro source directory.")
    val avroUnpackDependencies = taskKey[Seq[File]]("Unpack avro dependencies.")
    val avroDependencyIncludeFilter = settingKey[DependencyFilter]("Filter for including modules containing avro dependencies.")
    val avroGenerate = taskKey[Seq[File]]("Generate Java sources for Avro schemas.")
    val packageAvro = taskKey[File]("Produces an avro artifact, such as a jar containing avro schemas.")
    // format: on

    lazy val defaultSettings: Seq[Setting[_]] = Seq(
      avroDependencyIncludeFilter := artifactFilter(`type` = Artifact.SourceType, classifier = AvroClassifier)
    ) ++ addArtifact(Compile / packageAvro / artifact, Compile / packageAvro)

    // settings to be applied for both Compile and Test
    lazy val configScopedSettings: Seq[Setting[_]] = Seq(
      avroSource := sourceDirectory.value / "avro",
      // dependencies
      avroUnpackDependencies / target := sourceManaged.value / "avro",
      avroUnpackDependencies := unpackDependenciesTask(avroUnpackDependencies).value,
      // source generation
      avroGenerate / target := sourceManaged.value / "compiled_avro",
      avroGenerate := sourceGeneratorTask(avroGenerate).dependsOn(avroUnpackDependencies).value,
      sourceGenerators += avroGenerate.taskValue,
      compile := compile.dependsOn(avroGenerate).value,
      // packaging
      packageAvro / artifactClassifier := Some(AvroClassifier),
      packageAvro / publishArtifact := false,
      // clean
      clean := {
        schemaParser.set(new Schema.Parser())
        clean.value
      }
    ) ++ packageTaskSettings(packageAvro, packageAvroMappings) ++ Seq(
      packageAvro / artifact := (packageAvro / artifact).value.withType(Artifact.SourceType)
    )
  }

  import autoImport._

  def packageAvroMappings = Def.task {
    (avroSource.value ** AvroFilter) pair relativeTo(avroSource.value)
  }

  override def trigger: PluginTrigger = allRequirements

  override def requires: Plugins = sbt.plugins.JvmPlugin

  override lazy val globalSettings: Seq[Setting[_]] = Seq(
    avroStringType := "CharSequence",
    avroFieldVisibility := "public_deprecated",
    avroEnableDecimalLogicalType := true,
    avroUseNamespace := false
  )

  override lazy val projectSettings: Seq[Setting[_]] = defaultSettings ++
    Seq(Compile, Test).flatMap(c => inConfig(c)(configScopedSettings))

  private def unpack(deps: Seq[(ModuleID, File)],
                     extractTarget: File,
                     streams: TaskStreams): Seq[File] = {
    def cachedExtractDep(module: ModuleID, jar: File): Seq[File] = {
      val cached = FileFunction.cached(
        streams.cacheDirectory / jar.name,
        inStyle = FilesInfo.lastModified,
        outStyle = FilesInfo.exists
      ) { deps =>
        val moduleTarget = extractTarget / module.organization / module.name
        IO.createDirectory(moduleTarget)
        deps.flatMap { dep =>
          val set = IO.unzip(dep, moduleTarget, AvroFilter)
          if (set.nonEmpty) {
            streams.log.info(
              "Extracted from " + dep + set.mkString(":\n * ", "\n * ", "")
            )
          }
          set
        }
      }
      cached(Set(jar)).toSeq
    }

    deps.flatMap { case (module, jar) => cachedExtractDep(module, jar) }
  }

  private def unpackDependenciesTask(key: TaskKey[Seq[File]]) = Def.task {
    val avroArtifacts = update
      .value
      .filter((key / avroDependencyIncludeFilter).value)
      .toSeq.map {
      case (_, module, _, file) => module -> file
    }.distinct

    unpack(
      avroArtifacts,
      (key / target).value,
      (key / streams).value
    )
  }

  def compileIdl(idl: File, target: File, stringType: StringType, fieldVisibility: FieldVisibility, enableDecimalLogicalType: Boolean) {
    val parser = new Idl(idl)
    val protocol = Protocol.parse(parser.CompilationUnit.toString)
    val compiler = new SpecificCompiler(protocol)
    compiler.setStringType(stringType)
    compiler.setFieldVisibility(fieldVisibility)
    compiler.setEnableDecimalLogicalType(enableDecimalLogicalType)
    compiler.compileToDestination(null, target)
  }

  val schemaParser = new AtomicReference(new Schema.Parser())

  def compileAvscs(refs: Seq[AvroFileRef], target: File, stringType: StringType, fieldVisibility: FieldVisibility, enableDecimalLogicalType: Boolean, useNamespace: Boolean) {
    import com.spotify.avro.mojo._

    val global = schemaParser.get()
    // copy of global schemaParser to avoid race condition
    val parser = new Schema.Parser()
      .addTypes(global.getTypes)
      .setValidate(global.getValidate)
      .setValidateDefaults(global.getValidateDefaults)
    val compiler = new AvscFilesCompiler(parser)
    compiler.setStringType(stringType)
    compiler.setFieldVisibility(fieldVisibility)
    compiler.setUseNamespace(useNamespace)
    compiler.setEnableDecimalLogicalType(enableDecimalLogicalType)
    compiler.setCreateSetters(true)
    compiler.setLogCompileExceptions(true)
    compiler.setTemplateDirectory("/org/apache/avro/compiler/specific/templates/java/classic/")

    import scala.collection.JavaConverters._
    compiler.compileFiles(refs.toSet.asJava, target)
  }

  def compileAvpr(avpr: File, target: File, stringType: StringType, fieldVisibility: FieldVisibility, enableDecimalLogicalType: Boolean) {
    val protocol = Protocol.parse(avpr)
    val compiler = new SpecificCompiler(protocol)
    compiler.setStringType(stringType)
    compiler.setFieldVisibility(fieldVisibility)
    compiler.setEnableDecimalLogicalType(enableDecimalLogicalType)
    compiler.compileToDestination(null, target)
  }

  private[this] def compileAvroSchema(srcDir: File,
                                      target: File,
                                      log: Logger,
                                      stringType: StringType,
                                      fieldVisibility: FieldVisibility,
                                      enableDecimalLogicalType: Boolean,
                                      useNamespace: Boolean): Set[File] = {
    (srcDir ** AvroAvdlFilter).get.foreach { idl =>
      log.info(s"Compiling Avro IDL $idl")
      compileIdl(idl, target, stringType, fieldVisibility, enableDecimalLogicalType)
    }

    val avscs = (srcDir ** AvroAvscFilter).get.map { avsc =>
      log.info(s"Compiling Avro schemas $avsc")
      new AvroFileRef(srcDir, avsc.relativeTo(srcDir).get.toString)
    }
    compileAvscs(avscs, target, stringType, fieldVisibility, enableDecimalLogicalType, useNamespace)

    (srcDir ** AvroAvrpFilter).get.foreach { avpr =>
      log.info(s"Compiling Avro protocol $avpr")
      compileAvpr(avpr, target, stringType, fieldVisibility, enableDecimalLogicalType)
    }

    (target ** JavaFileFilter).get.toSet
  }

  private def sourceGeneratorTask(key: TaskKey[Seq[File]]) = Def.task {
    val out = (key / streams).value
    val externalSrcDir = (avroUnpackDependencies / target).value
    val srcDir = (key / avroSource).value
    val outDir = (key / target).value
    val strType = StringType.valueOf(avroStringType.value)
    val fieldVis = SpecificCompiler.FieldVisibility.valueOf(avroFieldVisibility.value.toUpperCase)
    val enbDecimal = avroEnableDecimalLogicalType.value
    val useNs = avroUseNamespace.value

    val cachedCompile = {
      FileFunction.cached(out.cacheDirectory / "avro", FilesInfo.lastModified, FilesInfo.exists) { _ =>
        out.log.info(s"Avro compiler using stringType=$strType")
        compileAvroSchema(externalSrcDir, outDir, out.log, strType, fieldVis, enbDecimal, useNs)
        compileAvroSchema(srcDir, outDir, out.log, strType, fieldVis, enbDecimal, useNs)
      }
    }

    cachedCompile(((externalSrcDir +++ srcDir) ** AvroFilter).get.toSet).toSeq
  }

}
