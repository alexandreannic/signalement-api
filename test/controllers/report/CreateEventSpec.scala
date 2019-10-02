package controllers.report

import java.time.OffsetDateTime
import java.util.UUID

import akka.util.Timeout
import com.google.inject.AbstractModule
import com.mohiva.play.silhouette.api.{Environment, LoginInfo}
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import com.mohiva.play.silhouette.test.{FakeEnvironment, _}
import controllers.ReportController
import models._
import net.codingwell.scalaguice.ScalaModule
import org.specs2.Spec
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mock.Mockito
import play.api.Configuration
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{Json, Writes}
import play.api.libs.mailer.{Attachment, AttachmentFile}
import play.api.mvc.Result
import play.api.test._
import play.mvc.Http.Status
import repositories.{EventRepository, ReportRepository, UserRepository}
import services.MailerService
import tasks.TasksModule
import utils.Constants.ActionEvent.ActionEventValue
import utils.Constants.EventType.EventTypeValue
import utils.Constants.StatusPro.StatusProValue
import utils.Constants.{ActionEvent, Departments, EventType, StatusPro}
import utils.silhouette.auth.AuthEnv

import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{Await, ExecutionContext, Future}


object CreateEventByUnauthenticatedUser extends CreateEventSpec  {
  override def is =
    s2"""
         Given an unauthenticated user                                ${step(someLoginInfo = None)}
         When create an event                                         ${step(someResult = Some(createEvent()))}
         Then user is not authorized                                  ${userMustBeUnauthorized}
    """
}

object CreateEventByNotConcernedProUser extends CreateEventSpec  {
  override def is =
    s2"""
         Given an authenticated pro user which is not concerned by the report   ${step(someLoginInfo = Some(notConcernedProLoginInfo))}
         When create an event                                                   ${step(someResult = Some(createEvent()))}
         Then user is not authorized                                            ${skipped(userMustBeUnauthorized)} //TODO add control in the controller
    """
}

object CreateEventAdminPostalMail extends CreateEventSpec {
  override def is =
    s2"""
        Given an authenticated admin user                                        ${step(someLoginInfo = Some(adminLoginInfo))}
        Given an action of sending postal letter                                 ${step(someEvent = Some(eventToCreate(EventType.PRO, ActionEvent.CONTACT_COURRIER)))}
        When create the associated event                                         ${step(someResult = Some(createEvent()))}
        Then an event "CONTACT_COURRIER" is created                              ${eventMustHaveBeenCreatedWithAction(ActionEvent.CONTACT_COURRIER)}
        And the report status is updated to "TRAITEMENT_EN_COURS"                ${reportMustHaveBeenUpdatedWithStatus(StatusPro.TRAITEMENT_EN_COURS)}
        no email is sent                                                         ${mailMustNotHaveBeenSent}
    """
}

object CreateEventProAnswer extends CreateEventSpec {
  override def is =
    s2"""
        Given an authenticated pro user which is concerned by the report         ${step(someLoginInfo = Some(concernedProLoginInfo))}
        Given a pro answer                                                       ${step(someEvent = Some(eventToCreate(EventType.PRO, ActionEvent.REPONSE_PRO_SIGNALEMENT)))}
        When create the associated event                                         ${step(someResult = Some(createEvent()))}
        Then an event "REPONSE_PRO_SIGNALEMENT" is created                       ${eventMustHaveBeenCreatedWithAction(ActionEvent.REPONSE_PRO_SIGNALEMENT)}
        And the report status is updated to "PROMESSE_ACTION"                    ${reportMustHaveBeenUpdatedWithStatus(StatusPro.PROMESSE_ACTION)}
        And an acknowledgment email is sent to the consumer                      ${mailMustHaveBeenSent(reportFixture.email,"Le professionnel a répondu à votre signalement", views.html.mails.consumer.reportToConsumerAcknowledgmentPro(reportFixture, someEvent.get).toString, Seq(AttachmentFile("logo-signal-conso.png", application.environment.getFile("/appfiles/logo-signal-conso.png"), contentId = Some("logo"))))}
        And an acknowledgment email is sent to the professional                  ${mailMustHaveBeenSent(concernedProUser.email.get,"Votre réponse au signalement", views.html.mails.professional.reportAcknowledgmentPro(someEvent.get, concernedProUser).toString, Seq(AttachmentFile("logo-signal-conso.png", application.environment.getFile("/appfiles/logo-signal-conso.png"), contentId = Some("logo"))))}
    """
}

object CreateEventProDeclinedAnswer extends CreateEventSpec {
  override def is =
    s2"""
        Given an authenticated pro user which is concerned by the report         ${step(someLoginInfo = Some(concernedProLoginInfo))}
        Given a pro answer who declines the report                               ${step(someEvent = Some(eventToCreate(EventType.PRO, ActionEvent.REPONSE_PRO_SIGNALEMENT, false)))}
        When create the associated event                                         ${step(someResult = Some(createEvent()))}
        Then an event "REPONSE_PRO_SIGNALEMENT" is created                       ${eventMustHaveBeenCreatedWithAction(ActionEvent.REPONSE_PRO_SIGNALEMENT)}
        And the report status is updated to "SIGNALEMENT_INFONDE"                ${reportMustHaveBeenUpdatedWithStatus(StatusPro.SIGNALEMENT_INFONDE)}
        And an acknowledgment email is sent to the consumer                      ${mailMustHaveBeenSent(reportFixture.email,"Le professionnel a répondu à votre signalement", views.html.mails.consumer.reportToConsumerAcknowledgmentPro(reportFixture, someEvent.get).toString, Seq(AttachmentFile("logo-signal-conso.png", application.environment.getFile("/appfiles/logo-signal-conso.png"), contentId = Some("logo"))))}
        And an acknowledgment email is sent to the professional                  ${mailMustHaveBeenSent(concernedProUser.email.get,"Votre réponse au signalement", views.html.mails.professional.reportAcknowledgmentPro(someEvent.get, concernedProUser).toString, Seq(AttachmentFile("logo-signal-conso.png", application.environment.getFile("/appfiles/logo-signal-conso.png"), contentId = Some("logo"))))}
    """
}

trait CreateEventSpec extends Spec with CreateEventContext {

  import org.specs2.matcher.MatchersImplicits._

  implicit val ee = ExecutionEnv.fromGlobalExecutionContext
  implicit val timeout: Timeout = 30.seconds

  implicit val actionEventValueWrites = new Writes[ActionEventValue] {
    def writes(actionEventValue: ActionEventValue) = Json.toJson(actionEventValue.value)
  }
  implicit val eventWriter = Json.writes[Event]

  var someLoginInfo: Option[LoginInfo] = None
  var someResult: Option[Result] = None
  var someEvent: Option[Event] = None

  def createEvent() =  {
    Await.result(
      application.injector.instanceOf[ReportController].createEvent(reportUUID.toString)
        .apply(someLoginInfo.map(FakeRequest().withAuthenticator[AuthEnv](_)).getOrElse(FakeRequest()).withBody(someEvent.map(Json.toJson(_)).getOrElse(Json.obj()))),
      Duration.Inf)
  }

  def userMustBeUnauthorized() = {
    someResult must beSome and someResult.get.header.status === Status.UNAUTHORIZED
  }

  def eventMustHaveBeenCreatedWithAction(action: ActionEventValue) = {
    there was one(mockEventRepository).createEvent(argThat(eventActionMatcher(action)))
  }

  def eventActionMatcher(action: ActionEventValue): org.specs2.matcher.Matcher[Event] = { event: Event =>
    (action == event.action, s"action doesn't match ${action}")
  }

  def reportMustHaveBeenUpdatedWithStatus(status: StatusProValue) = {
    there was one(mockReportRepository).update(argThat(reportStatusProMatcher(Some(status))))
  }

  def reportStatusProMatcher(status: Option[StatusProValue]): org.specs2.matcher.Matcher[Report] = { report: Report =>
    (status == report.statusPro, s"status doesn't match ${status}")
  }

}

trait CreateEventContext extends Mockito {

  implicit val ec = ExecutionContext.global

  val reportUUID = UUID.randomUUID()

  val reportFixture = Report(
    Some(reportUUID), "category", List("subcategory"), List(), "companyName", "companyAddress", Some(Departments.AUTHORIZED(0)), Some("00000000000000"), Some(OffsetDateTime.now()),
    "firstName", "lastName", "email", true, List(), None
  )

  def mailMustHaveBeenSent(recipient: String, subject: String, bodyHtml: String, attachments: Seq[Attachment] = null) = {
    there was one(application.injector.instanceOf[MailerService])
      .sendEmail(
        application.configuration.get[String]("play.mail.from"),
        recipient
      )(
        subject,
        bodyHtml,
        attachments
      )
  }

  def mailMustNotHaveBeenSent() = {
    there was no(application.injector.instanceOf[MailerService]).sendEmail(anyString, anyString)(anyString, anyString, any)
  }

  def eventToCreate(eventType: EventTypeValue, action: ActionEventValue, withResult: Boolean = true) =
    Event(None, Some(reportUUID), concernedProUser.id, None, eventType, action, Some(withResult), None)

  val siretForConcernedPro = "000000000000000"
  val siretForNotConcernedPro = "11111111111111"

  val adminUser = User(UUID.randomUUID(), "admin@signalconso.beta.gouv.fr", "password", None, Some("Prénom"), Some("Nom"), Some("admin@signalconso.beta.gouv.fr"), UserRoles.Admin)
  val adminLoginInfo = LoginInfo(CredentialsProvider.ID, adminUser.login)

  val concernedProUser = User(UUID.randomUUID(), siretForConcernedPro, "password", None, Some("Prénom"), Some("Nom"), Some("pro@signalconso.beta.gouv.fr"), UserRoles.Pro)
  val concernedProLoginInfo = LoginInfo(CredentialsProvider.ID, concernedProUser.login)

  val notConcernedProUser = User(UUID.randomUUID(), siretForNotConcernedPro, "password", None, Some("Prénom"), Some("Nom"), Some("pro@signalconso.beta.gouv.fr"), UserRoles.Pro)
  val notConcernedProLoginInfo = LoginInfo(CredentialsProvider.ID, notConcernedProUser.login)

  implicit val env: Environment[AuthEnv] = new FakeEnvironment[AuthEnv](Seq(adminLoginInfo -> adminUser, concernedProLoginInfo -> concernedProUser, notConcernedProLoginInfo -> notConcernedProUser))

  val mockReportRepository = mock[ReportRepository]
  val mockEventRepository = mock[EventRepository]
  val mockMailerService = mock[MailerService]
  val mockUserRepository = mock[UserRepository]

  mockReportRepository.getReport(reportUUID) returns Future(Some(reportFixture))
  mockReportRepository.update(any[Report]) answers { report => Future(report.asInstanceOf[Report]) }

  mockUserRepository.get(concernedProUser.id) returns Future(Some(concernedProUser))

  mockEventRepository.createEvent(any[Event]) answers { event => Future(event.asInstanceOf[Event]) }

  class FakeModule extends AbstractModule with ScalaModule {
    override def configure() = {
      bind[Environment[AuthEnv]].toInstance(env)
      bind[ReportRepository].toInstance(mockReportRepository)
      bind[EventRepository].toInstance(mockEventRepository)
      bind[MailerService].toInstance(mockMailerService)
      bind[UserRepository].toInstance(mockUserRepository)
    }
  }

  lazy val application = new GuiceApplicationBuilder()
    .configure(
      Configuration(
        "play.evolutions.enabled" -> false,
        "slick.dbs.default.db.connectionPool" -> "disabled",
        "play.mailer.mock" -> true
      )
    )
    .disable[TasksModule]
    .overrides(new FakeModule())
    .build()

}