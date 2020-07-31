import java.util.Collections
import org.apache.avro.Schema

name := "avscparser-test"

libraryDependencies ++= Seq(
  "org.apache.avro" % "avro" % "1.10.0",
  "org.specs2" %% "specs2-core" % "4.9.4" % Test
)

avroSchemaParserBuilder := AnnotateWithArtifactSchemaParser
  .newBuilder(projectID.value)
  .withTypes(Collections.singletonMap(
    "B", Schema.createEnum("B", null, "com.cavorite.test.avscparser", Collections.singletonList("B1"))
  ))
