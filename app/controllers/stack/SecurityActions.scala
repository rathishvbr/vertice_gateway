/*
** Copyright [2013-2015] [Megam Systems]
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
** http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/
package controllers.stack

import scalaz._
import Scalaz._
import scalaz.Validation._
import scalaz.effect.IO
import scalaz.EitherT._
import scalaz.NonEmptyList._

import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac
import org.apache.commons.codec.binary.Base64
import jp.t2v.lab.play2.stackc.{ RequestWithAttributes, RequestAttributeKey, StackableController }

import controllers.funnel._
import controllers.funnel.FunnelErrors._
import models.base.{Accounts, AccountResult}
import controllers.stack._
import play.api.mvc._
import play.api.http.Status._
import play.api.Logger
/**
 * @author rajthilak
 *
 */
 case class AuthBag(email: String, api_key: String, authority: String)

object SecurityActions {


  def Authenticated[A](req: FunnelRequestBuilder[A]): ValidationNel[Throwable, Option[AuthBag]] = {
    req.funneled match {
      case Success(succ) => {
        (succ map (x => bazookaAtDataSource(x))).getOrElse(
          Validation.failure[Throwable, Option[AuthBag]](CannotAuthenticateError("""Invalid content in header. parse failure.""",
            "Request can't be funneled.")).toValidationNel)

      }
      case Failure(err) =>
        val errm = (err.list.map(m => m.getMessage)).mkString("\n")
        Validation.failure[Error, Option[AuthBag]](CannotAuthenticateError(
          """Invalid content in header. parse failure.""", errm)).toValidationNel
    }
  }

  /**
   * This Authenticated function will extract information from the request and calculate
   * an HMAC value. The request is parsed as tolerant text, as content type is application/json,
   * which isn't picked up by the default body parsers in the controller.
   * If the header exists then
   * the string is split on : and the header is parsed
   * else
   */
  def bazookaAtDataSource(freq: FunneledRequest): ValidationNel[Throwable, Option[AuthBag]] = {
    (for {
      resp <- eitherT[IO, NonEmptyList[Throwable], Option[AccountResult]] { //disjunction Throwabel \/ Option with a Function IO.
        (Accounts.findByEmail(freq.maybeEmail.get).disjunction).pure[IO]
      }
      found <- eitherT[IO, NonEmptyList[Throwable], Option[AuthBag]] {
        val fres = resp.get
        var calculatedHMACAPIKEY   = ""
        var calculatedHMACPASSWORD = ""
        var flag =false
      if (freq.clientAPIPuttusavi != None){
          if (freq.clientAPIPuttusavi.get == "true") {
            calculatedHMACPASSWORD = GoofyCrypto.calculateHMAC(fres.password, freq.mkSign)
         }else {
             flag = true
        }
    }else {
        flag =true
    }
    if (flag) {
    calculatedHMACAPIKEY = GoofyCrypto.calculateHMAC(fres.api_key, freq.mkSign)
    }
        if (calculatedHMACAPIKEY === freq.clientAPIHmac.get) {
          (AuthBag(fres.email, fres.api_key, fres.authority).some).right[NonEmptyList[Throwable]].pure[IO]
        } else if (calculatedHMACPASSWORD === freq.clientAPIHmac.get) {
          (AuthBag(fres.email, fres.password, fres.authority).some).right[NonEmptyList[Throwable]].pure[IO]
        }else {
          (nels((CannotAuthenticateError("""Authorization failure for 'email:' HMAC doesn't match: '%s'."""
            .format(fres.email).stripMargin, "", UNAUTHORIZED))): NonEmptyList[Throwable]).left[Option[AuthBag]].pure[IO]
        }
      }
    } yield found).run.map(_.validation).unsafePerformIO()
  }
}

/**
 * GoofyCrypto just provides methods to make a content into MD5,
 * calculate a HMACSHA1, using a RAW secret (api_key). -- TO-DO change the api_key as SHA1.
 */
object GoofyCrypto {
  /**
   * Calculate the MD5 hash for the specified content (UTF-16 encoded)
   */
  def calculateMD5(content: Option[String]): Option[String] = {
    val MD5 = "MD5"
    val digest = MessageDigest.getInstance(MD5)
    digest.update(content.getOrElse(new String()).getBytes)
    val md5b = new String(Base64.encodeBase64(digest.digest()))
    md5b.some
  }

  /**
   * Calculate the HMAC for the specified data and the supplied secret (UTF-16 encoded)
   */
  def calculateHMAC(secret: String, toEncode: String): String = {
    val HMACSHA1 = "HmacSHA1"
    val signingKey = new SecretKeySpec(secret.getBytes(), "RAW")
    val mac = Mac.getInstance(HMACSHA1)
    mac.init(signingKey)
    val rawHmac = mac.doFinal(toEncode.getBytes())
    val hmacAsByt = dumpByt(rawHmac.some)
    hmacAsByt
  }

  def dumpByt(bytesOpt: Option[Array[Byte]]): String = {
    val b: Array[String] = (bytesOpt match {
      case Some(bytes) => bytes.map(byt => (("00" + (byt &
        0XFF).toHexString)).takeRight(2))
      case None => Array(0X00.toHexString)
    })
    b.mkString("")
  }

}
