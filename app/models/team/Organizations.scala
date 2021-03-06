package models.team

import scalaz._
import Scalaz._
import scalaz.effect.IO
import scalaz.EitherT._
import scalaz.Validation
import scalaz.Validation.FlatMap._
import scalaz.NonEmptyList._
import scalaz.syntax.SemigroupOps

import models.json._
import models.base._
import db._
import cache._
import app.MConfig
import models.Constants._
import io.megam.auth.funnel.FunnelErrors._
import scalaz.Validation
import scalaz.Validation.FlatMap._

import io.megam.util.Time
import io.megam.common.uid.UID
import net.liftweb.json._
import net.liftweb.json.scalaz.JsonScalaz._
import java.nio.charset.Charset
//import com.twitter.util.{ Future, Await }
import scala.concurrent.Await
import scala.concurrent.duration._

//import com.twitter.conversions.time._
import org.joda.time.DateTime

import com.websudos.phantom.dsl._
import com.websudos.phantom.iteratee.Iteratee
import com.websudos.phantom.connectors.{ ContactPoint, KeySpaceDef }

/**
 *
 * @author morpheyesh
 */

case class OrganizationsInput(name: String) {
  val json = "{\"name\":\"" + name + "\"}"
}

case class OrganizationsInviteInput(id: String) {
  val json = "{\"id\":\"" + id + "\"}"
}

case class OrganizationsResult(
  id: String,
  accounts_id: String,
  name: String,
  json_claz: String,
  created_at: String) {}

object OrganizationsResult {
  val empty = new OrganizationsResult("", "", "", "Megam::Organizations", "")
}

sealed class OrganizationsT extends CassandraTable[OrganizationsT, OrganizationsResult] {

  object id extends StringColumn(this) with PrimaryKey[String]
  object accounts_id extends StringColumn(this) with PartitionKey[String]
  object name extends StringColumn(this)
  object json_claz extends StringColumn(this)
  object created_at extends StringColumn(this)

  override def fromRow(r: Row): OrganizationsResult = {
    OrganizationsResult(
      id(r),
      accounts_id(r),
      name(r),
      json_claz(r),
      created_at(r))
  }
}

/*
 *   This class talks to scylla and performs the actions
 */

abstract class ConcreteOrg extends OrganizationsT with ScyllaConnector {

  override lazy val tableName = "organizations"

  def insertNewRecord(org: OrganizationsResult): ResultSet = {
    val res = insert.value(_.id, org.id)
      .value(_.accounts_id, org.accounts_id)
      .value(_.name, org.name)
      .value(_.json_claz, org.json_claz)
      .value(_.created_at, org.created_at)
      .future()
    Await.result(res, 5.seconds)
  }

}

object Organizations extends ConcreteOrg {

  implicit val formats = DefaultFormats

  private def orgNel(input: String): ValidationNel[Throwable, OrganizationsInput] = {
    (Validation.fromTryCatchThrowable[OrganizationsInput, Throwable] {
      parse(input).extract[OrganizationsInput]
    } leftMap { t: Throwable => new MalformedBodyError(input, t.getMessage) }).toValidationNel
  }

  private def inviteNel(input: String): ValidationNel[Throwable, OrganizationsInviteInput] = {
    (Validation.fromTryCatchThrowable[OrganizationsInviteInput, Throwable] {
      parse(input).extract[OrganizationsInviteInput]
    } leftMap { t: Throwable => new MalformedBodyError(input, t.getMessage) }).toValidationNel
  }

  private def organizationsSet(id: String, email: String, c: OrganizationsInput): ValidationNel[Throwable, OrganizationsResult] = {
    (Validation.fromTryCatchThrowable[OrganizationsResult, Throwable] {
      OrganizationsResult(id, email, c.name, "Megam::Organizations", Time.now.toString)
    } leftMap { t: Throwable => new MalformedBodyError(c.json, t.getMessage) }).toValidationNel
  }

  def create(email: String, input: String): ValidationNel[Throwable, Option[OrganizationsResult]] = {
    for {
      c <- orgNel(input)
      uir <- (UID("org").get leftMap { u: NonEmptyList[Throwable] => u })
      org <- organizationsSet(uir.get._1 + uir.get._2, email, c)
      //  s <-
    } yield {
      insertNewRecord(org)
      org.some
    }
  }

  def findByEmail(accounts_id: String): ValidationNel[Throwable, Seq[OrganizationsResult]] = {
    val resp = select.allowFiltering().where(_.accounts_id eqs accounts_id).fetch()
    (Await.result(resp, 5.seconds)).successNel
  }

  private def findById(id: String): ValidationNel[Throwable, Option[OrganizationsResult]] = {
    val resp = select.allowFiltering().where(_.id eqs id).one()
    (Await.result(resp, 5.second)).successNel
  }

  def inviteOrganization(email: String, input: String): ValidationNel[Throwable, ResultSet] = {
    for {
      c <- inviteNel(input)
      upd <- findById(c.id)
    } yield {
      val org = new OrganizationsResult(upd.head.id, email, upd.head.name, upd.head.json_claz, Time.now.toString)
      insertNewRecord(org)
    }
  }
}
