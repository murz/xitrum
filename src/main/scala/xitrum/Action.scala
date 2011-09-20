package xitrum

import java.io.File
import scala.xml.Elem
import scala.util.Random

import org.jboss.netty.handler.codec.http._
import HttpHeaders.Names._
import HttpResponseStatus._

import xitrum.action._
import xitrum.handler.up.Dispatcher
import xitrum.routing.{PostbackAction, Routes}
import xitrum.scope.request.ExtEnv
import xitrum.scope.session.SecureBase64
import xitrum.etag.{Etag, NotModified}
import xitrum.validation.{Validator, ValidatorInjector}
import xitrum.view.Renderer

trait Action extends ExtEnv with Logger with Net with Filter with BasicAuthentication with Renderer with I18n {
  def execute {}
  def postback {}

  //----------------------------------------------------------------------------

  // For Validate to use
  implicit val action: Action = this

  //----------------------------------------------------------------------------

  private var responded = false

  def isResponded = responded

  def respond = synchronized {
    if (responded) {
      // Print the stack trace so that application developers know where to fix
      try {
        throw new Exception
      } catch {
        case e => logger.warn("Double response", e)
      }
    } else {
      responded = true
      if (channel.isOpen) {
        prepareWhenRespond
        handlerEnv.response = response
        channel.write(handlerEnv)
      }
    }
  }

  //----------------------------------------------------------------------------

  def urlFor[T: Manifest](params: (String, Any)*) = {
    val actionClass = manifest[T].erasure.asInstanceOf[Class[Action]]
    Routes.urlFor(actionClass, params:_*)
  }
  def absoluteUrlFor[T: Manifest](params: (String, Any)*) = absoluteUrlPrefix + urlFor[T](params:_*)

  /**
   * When there are no params, the application developer can write
   * urlFor[MyAction], instead of urlFor[MyAction]().
   */
  def urlFor[T: Manifest]: String = urlFor[T]()
  def absoluteUrlFor[T: Manifest]: String = absoluteUrlPrefix + urlFor[T]()

  def urlForThis(params: (String, Any)*) = Routes.urlFor(this.getClass.asInstanceOf[Class[Action]], params:_*)
  def absoluteUrlForThis(params: (String, Any)*) = absoluteUrlPrefix + urlForThis(params:_*)

  def urlForThis: String = urlForThis()
  def absoluteUrlForThis = absoluteUrlPrefix + urlForThis

  //----------------------------------------------------------------------------

  def urlForPostback[T: Manifest](extraParams: (String, Any)*): String = {
    val actionClass = manifest[T].erasure.asInstanceOf[Class[Action]]
    val url = urlForPostbackAction(actionClass)
    registerExtraParams(url, extraParams)
    url
  }

  def urlForPostbackThis(extraParams: (String, Any)*) = {
    val actionClass = this.getClass.asInstanceOf[Class[Action]]
    val url = urlForPostbackAction(actionClass)
    registerExtraParams(url, extraParams)
    url
  }

  def urlForPostback[T: Manifest]: String = urlForPostback[T]()
  def urlForPostbackThis: String          = urlForPostbackThis()

  private def urlForPostbackAction(actionClass: Class[Action]): String = {
    val className       = actionClass.getName
    val secureClassName = SecureBase64.encrypt(className)
    val url = PostbackAction.POSTBACK_PREFIX + secureClassName
    if (Config.baseUri.isEmpty) url else Config.baseUri + "/" + url
  }

  private def registerExtraParams(url: String, extraParams: Iterable[(String, Any)]) {
    if (extraParams.isEmpty) return

    val e = new QueryStringEncoder("")
    extraParams.foreach { case (paramName, value) =>
      val secureParamName = ValidatorInjector.injectToParamName(paramName)
      e.addParam(secureParamName, value.toString)
    }

    val withLeadingQuestionMark    = e.toString  // ?p1=v1&p2=v2
    val withoutLeadingQuestionMark = withLeadingQuestionMark.substring(1)
    jsAddToView("$(\"[action='" + url + "']\").data(\"extra\", '" + withoutLeadingQuestionMark + "')")
  }

  //----------------------------------------------------------------------------

  def urlForPublic(path: String) = {
    val absPath     = System.getProperty("user.dir") + "/static/public/" + path
    val forceReload = Etag.forFile(absPath) match {
      case Etag.NotFound                           => Random.nextLong.toString
      case Etag.TooBig(file)                       => file.lastModified
      case Etag.Small(bytes, etag, mimeo, gzipped) => etag
    }
    Config.baseUri + "/public/" + path + "?" + forceReload
  }

  def urlForResource(path: String) = {
    val forceReload = Etag.forResource(path) match {
      case Etag.NotFound                           => NotModified.serverStartupTimestamp.toString
      case Etag.Small(bytes, etag, mimeo, gzipped) => etag
    }
    Config.baseUri + "/resources/public/" + path + "?" + forceReload
  }

  //----------------------------------------------------------------------------

  def redirectTo(location: String, status: HttpResponseStatus = FOUND) {
    if (!channel.isOpen) return

    response.setStatus(status)

    HttpHeaders.setContentLength(response, 0)
    response.setHeader(LOCATION, location)
    respond
  }

  def redirectTo[T: Manifest] { redirectTo(urlFor[T]) }

  def redirectTo[T: Manifest](params: (String, Any)*) { redirectTo(urlFor[T](params:_*)) }

  //----------------------------------------------------------------------------

  var isPostback: Boolean = _  // Set to true by PostbackAction
  {
    isPostback = false
  }

  def forward(actionClass: Class[Action], postback: Boolean) {
    val action = actionClass.newInstance
    action(channel, handlerEnv)
    action.isPostback = isPostback
    Dispatcher.dispatchWithFailsafe(action, postback)
  }

  //----------------------------------------------------------------------------

  /** @return Param name that has been encrypted to include serialized validators */
  def validate(paramName: String, validators: Validator*): String = {
    /* Design decision:
    App developers would write:
      <input type="text" name={validate("username", MinLength(5), MaxLength(10)} />

    This is easier to read and simpler than:
      {<input type="text" name="username" /> +: Validate(MinLength(5), MaxLength(10))}
    and he know that the resulting name may not be "username".

    This is faster than:
      {<input type="text" name="username" /> +: MinLength(5) +: MaxLength(10)}
    */

    val secureParamName = ValidatorInjector.injectToParamName(paramName, validators:_*)
    validators.foreach { v => v.render(this, paramName, secureParamName) }
    secureParamName
  }

  //----------------------------------------------------------------------------

  def addConnectionClosedListener(listener: () => Unit) {
    val dispatcher = channel.getPipeline.get(classOf[Dispatcher])
    dispatcher.addConnectionClosedListener(listener)
  }
}
