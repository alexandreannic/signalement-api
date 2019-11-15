package utils

import java.util.UUID
import org.scalacheck._
import org.scalacheck.Arbitrary._

import models._

object Fixtures {
    val genUser = for {
        id <- arbitrary[UUID]
        login <- arbitrary[String]
        password <- arbitrary[String]
        activationKey <- arbitrary[String]
        firstName <- Gen.oneOf("Alice", "Bob", "Charles", "Danièle", "Émilien", "Fanny", "Gérard")
        lastName <- Gen.oneOf("Doe", "Durand", "Dupont")
        userRole <- Gen.oneOf(UserRoles.userRoles)
        randInt <- Gen.choose(0, 1000000)
    } yield User(
        id, login, password, Some(activationKey),
        Some(EmailAddress(s"${firstName}.${lastName}.${randInt}@example.com")),
        Some(firstName), Some(lastName), userRole
    )

    val genAdminUser = genUser.map(_.copy(userRole = UserRoles.Admin))
    val genProUser = genUser.map(_.copy(userRole = UserRoles.Pro))
    val genToActivateUser = genUser.map(_.copy(userRole = UserRoles.ToActivate))
}
