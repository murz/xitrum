package xt.vc

import xt._

import java.lang.reflect.Method

import org.jboss.netty.handler.codec.http.HttpMethod

object Router {
  type Route           = (HttpMethod, String, String)
  type CompiledPattern = Array[(String, Boolean)]  // String: token, Boolean: true if the token is constant
  type Csas            = (String, String)
  type KA              = (Class[Controller], Method)
  type CompiledCsas    = (Csas, KA)
  type CompiledRoute   = (HttpMethod, CompiledPattern, Csas, KA)

  private var compiledRoutes: Iterable[CompiledRoute] = _

  /**
   * Application that does not use view (Scalate view) does not have to specify viewPaths.
   */
  def apply(routes: List[Route], controllerPaths: List[String]) = {
    compiledRoutes = routes.map(compileRoute(_, controllerPaths))
  }

  /**
   * @return None if not matched or Some(routeParams)
   *
   * controller name and action name are put int routeParams.
   */
  def matchRoute(method: HttpMethod, pathInfo: String): Option[(KA, Env.Params)] = {
    val tokens = pathInfo.split("/").filter(_ != "")
    val max1   = tokens.size

    var routeParams: Env.Params = null

    val finder = (cr: CompiledRoute) => {
      val (m, compiledPattern, csas, compiledCA) = cr

      // Must be <= max1
      // If < max1, the last token must be "*" and non-fixed
      val max2 = compiledPattern.size

      if (max2 > max1 || m != method)
        false
      else {
        if (max2 == 0) {
          if (max1 == 0) {
            routeParams = new java.util.LinkedHashMap[String, java.util.List[String]]()
            true
          } else
            false
        } else {
          routeParams = new java.util.LinkedHashMap[String, java.util.List[String]]()
          var i = 0  // i will go from 0 until max1

          compiledPattern.forall { tc =>
            val (token, constant) = tc
            val ret = if (constant)
              (token == tokens(i))
            else {
              if (i == max2 - 1) {
                if (token == "*") {
                  val value = tokens.slice(i, max1).mkString("/")
                  routeParams.put(token, toValues(value))
                  true
                } else {
                  if (max2 < max1) {
                    false
                  } else {  // max2 = max1
                    val value = tokens(i)
                    routeParams.put(token, toValues(value))
                    true
                  }
                }
              } else {  // Not the last token
                if (token == "*") {
                  false
                } else {
                  URLDecoder.decode(tokens(i)) match {
                    case None => false

                    case Some(value) =>
                      routeParams.put(token, toValues(value))
                      true
                  }
                }
              }
            }

            i += 1
            ret
          }
        }
      }
    }

    compiledRoutes.find(finder) match {
      case Some(cr) =>
        val (m, compiledPattern, csas, compiledKA) = cr
        val (cs, as) = csas
        routeParams.put("controller", toValues(cs))
        routeParams.put("action",     toValues(as))
        Some((compiledKA, routeParams))

      case None => None
    }
  }

  /**
   * WARN: This method is here because it is also used by Failsafe when redispatching.
   *
   * Wraps a single String by a List.
   */
  def toValues(value: String): java.util.List[String] = {
    val values = new java.util.ArrayList[String](1)
    values.add(value)
    values
  }

  //----------------------------------------------------------------------------

  private def compileRoute(route: Route, controllerPaths: List[String]): CompiledRoute = {
    val (method, pattern, csas) = route
    val cp = compilePattern(pattern)
    val (csast, ka) = compileCsas(csas, controllerPaths)
    (method, cp, csast, ka)
  }

  private def compilePattern(pattern: String): CompiledPattern = {
    val tokens = pattern.split("/").filter(_ != "")
    tokens.map { e: String =>
      val constant = !e.startsWith(":")
      val token    = if (constant) e else e.substring(1)
      (token, constant)
    }
  }

  /**
   * Given "Articles#index", rerturns:
   * ("Articles", "index", Articles class, index method)
   */
  private def compileCsas(csas: String, controllerPaths: List[String]): CompiledCsas = {
    val caa = csas.split("#")
    val cs  = caa(0)
    val as  = caa(1)

    var k: Class[Controller] = null
    controllerPaths.find { p =>
      try {
        k = Class.forName(p + "." + cs).asInstanceOf[Class[Controller]]
        true
      } catch {
        case _ => false
      }
    }
    if (k == null) throw(new Exception("Could not load " + csas))

    val a  = k.getMethod(as)

    ((cs, as), (k, a))
  }

  // Same as Rails' config.filter_parameters
  private def filterParams(params: java.util.Map[String, java.util.List[String]]): java.util.Map[String, java.util.List[String]] = {
    val ret = new java.util.LinkedHashMap[String, java.util.List[String]]()
    ret.putAll(params)
    for (key <- Config.filterParams) {
      if (ret.containsKey(key)) ret.put(key, toValues("[filtered]"))
    }
    ret
  }
}