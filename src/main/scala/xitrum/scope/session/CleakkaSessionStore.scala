package xitrum.scope.session

import java.util.UUID
import scala.collection.mutable.HashMap
import scala.util.control.NonFatal

import org.jboss.netty.handler.codec.http.DefaultCookie

import xitrum.{Cache, Config}
import xitrum.util.SecureUrlSafeBase64

/**
 * @param sessionId needed to save the session to Cleakka
 *
 * @param newlyCreated false means sessionId is taken from valid session cookie
 * sent by browser, true means browser did not send session cookie or did send
 * but the cookie value is not a valid encrypted session ID
 *
 * Subclass of HashMap => subclass of mutable Map = Session
 */
class CleakkaSession(val sessionId: String, val newlyCreated: Boolean) extends HashMap[String, Any]

class CleakkaSessionStore extends SessionStore {
  def restore(env: SessionEnv): Session = {
    val sessionCookieName = Config.xitrum.session.cookieName
    env.requestCookies.get(sessionCookieName) match {
      case None =>
        val sessionId = UUID.randomUUID().toString
        new CleakkaSession(sessionId, true)

      case Some(encryptedSessionId) =>
        SecureUrlSafeBase64.decrypt(encryptedSessionId) match {
          case None =>
            // sessionId sent by browser is invalid, recreate
            val sessionId = UUID.randomUUID().toString
            new CleakkaSession(sessionId, true)

          case Some(any) =>
            val sessionIdo =
              try {
                Option(any.asInstanceOf[String])
              } catch {
                case NonFatal(e) => None
              }

            sessionIdo match {
              case None =>
                // sessionId sent by browser is not a String
                // (due to the switch from CookieSessionStore to CleakkaSessionStore etc.),
                // recreate
                val sessionId = UUID.randomUUID().toString
                new CleakkaSession(sessionId, true)

              case Some(sessionId) =>
                // See "store" method to know why this map is immutable
                val immutableMapo = Cache.get[Map[String, Any]](sessionId)

                val ret = new CleakkaSession(sessionId, false)
                immutableMapo.foreach { ret ++= _ }
                ret
            }
        }
    }
  }

  /** @param session has been restored by "restore" method */
  def store(session: Session, env: SessionEnv) {
    val sessionCookieName = Config.xitrum.session.cookieName
    if (session.isEmpty) {
      // If session cookie has been sent by browser, send back session cookie
      // with max age = 0 so that browser will delete it immediately
      if (env.requestCookies.isDefinedAt(sessionCookieName)) {
        val cookie = new DefaultCookie(sessionCookieName, "0")
        cookie.setHttpOnly(true)
        cookie.setMaxAge(0)
        env.responseCookies.append(cookie)

        // Remove session in Cleakka if any
        val hSession = session.asInstanceOf[CleakkaSession]
        if (!hSession.newlyCreated) Cache.remove(hSession.sessionId)
      }
    } else {
      val hSession = session.asInstanceOf[CleakkaSession]
      // newlyCreated: true means browser did not send session cookie or did send
      // but the cookie value is not a valid encrypted session ID
      if (hSession.newlyCreated) {
        // DefaultCookie has max age of Integer.MIN_VALUE by default,
        // which means the cookie will be removed when user terminates browser
        val cookie = new DefaultCookie(sessionCookieName, SecureUrlSafeBase64.encrypt(hSession.sessionId))
        cookie.setHttpOnly(true)
        env.responseCookies.append(cookie)
      }

      // See "restore" method
      // Convert to immutable because mutable cannot always be deserialized later!
      val immutableMap = session.toMap
      Cache.put(hSession.sessionId, immutableMap)
    }
  }
}
