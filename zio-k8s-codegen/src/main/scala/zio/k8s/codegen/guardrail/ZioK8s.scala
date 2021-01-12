package zio.k8s.codegen.guardrail

import com.twilio.guardrail.Target
import com.twilio.guardrail.generators.Scala.model.CirceModelGenerator
import com.twilio.guardrail.generators.Scala.{ AkkaHttpGenerator, CirceProtocolGenerator }
import com.twilio.guardrail.generators.ScalaGenerator.ScalaInterp
import com.twilio.guardrail.generators.collections.ScalaCollectionsGenerator.ScalaCollectionsInterp
import com.twilio.guardrail.generators.{ Framework, SwaggerGenerator }
import com.twilio.guardrail.languages.ScalaLanguage

// Custom code generator framework

// if we will start generating models from the k8s OpenAPI specs we want to customize
// the generated models (naming, split to package)
// if we want to generate clients too then SttpK8sClient has to be implemented
class ZioK8s(implicit k8sContext: K8sCodegenContext) extends Framework[ScalaLanguage, Target] {
  implicit def CollectionsLibInterp = ScalaCollectionsInterp
  implicit def ArrayProtocolInterp = new CirceProtocolGenerator.ArrayProtocolTermInterp
  implicit def ClientInterp = new SttpK8sClient
  implicit def EnumProtocolInterp = new CirceProtocolGenerator.EnumProtocolTermInterp
  implicit def FrameworkInterp = new AkkaHttpGenerator.FrameworkInterp(CirceModelGenerator.V012)
  implicit def ModelProtocolInterp = new K8sModelProtocolTermInterp()
  implicit def PolyProtocolInterp = new CirceProtocolGenerator.PolyProtocolTermInterp
  implicit def ProtocolSupportInterp = new CirceProtocolGenerator.ProtocolSupportTermInterp
  implicit def ServerInterp = new NoServerSupport
  implicit def SwaggerInterp = SwaggerGenerator[ScalaLanguage]
  implicit def LanguageInterp = ScalaInterp
}
