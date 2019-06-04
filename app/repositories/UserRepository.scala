package repositories

import java.util.UUID

import javax.inject.{Inject, Singleton}
import models.{User, UserRoles}
import play.api.db.slick.DatabaseConfigProvider
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

/**
 * A repository for user.
 *
 * @param dbConfigProvider The Play db config provider. Play will inject this for you.
 */
@Singleton
class UserRepository @Inject()(dbConfigProvider: DatabaseConfigProvider)(implicit ec: ExecutionContext) {

  private val dbConfig = dbConfigProvider.get[JdbcProfile]

 import dbConfig._
 import profile.api._


  private class UserTable(tag: Tag) extends Table[User](tag, "users") {

    def id = column[UUID]("id", O.PrimaryKey)

    def login = column[String]("login")
    def password = column[String]("password")
    def activationKey = column[Option[String]]("activation_key")
    def email = column[Option[String]]("email")
    def firstName = column[Option[String]]("firstname")
    def lastName = column[Option[String]]("lastname")
    def role = column[String]("role")

    type UserData = (UUID, String, String, Option[String], Option[String], Option[String], Option[String], String)

    def constructUser: UserData => User = {
      case (id, login, password, activationKey, email, firstName, lastName, role) => User(id, login, password, activationKey, email, firstName, lastName, UserRoles.withName(role))
    }

    def extractUser: PartialFunction[User, UserData] = {
      case User(id, login, password, activationKey, email, firstName, lastName, role) => (id, login, password, activationKey, email, firstName, lastName, role.name)
    }

    def * = (id, login, password, activationKey, email, firstName, lastName, role) <> (constructUser, extractUser.lift)
  }

  private val userTableQuery = TableQuery[UserTable]
  
  def list: Future[Seq[User]] = db.run(userTableQuery.result)

  def create(user: User): Future[User] = db
    .run(userTableQuery += user)
    .map(_ => user)

  def get(userId: UUID): Future[Option[User]] = db
    .run(userTableQuery.filter(_.id === userId).to[List].result.headOption)

  def update(user: User): Future[Int] = {
    val queryUser = for (refUser <- userTableQuery if refUser.id === user.id)
      yield refUser
    db.run(
      queryUser
        .map(u => (u.firstName, u.lastName, u.email, u.role))
        .update(user.firstName, user.lastName, user.email,  user.userRole.name)
    )
  }

  def updatePassword(userId: UUID, password: String): Future[Int] = {
    val queryUser = for (refUser <- userTableQuery if refUser.id === userId)
      yield refUser
    db.run(
      queryUser
        .map(u => (u.password))
        .update(password)
    )
  }

  def delete(userId: UUID): Future[Int] = db
    .run(userTableQuery.filter(_.id === userId).delete)

  def delete(email: String): Future[Int] = db
    .run(userTableQuery.filter(_.email === email).delete)

  def findByLogin(login: String): Future[Option[User]] = db
    .run(userTableQuery.filter(_.login === login).to[List].result.headOption)

}
