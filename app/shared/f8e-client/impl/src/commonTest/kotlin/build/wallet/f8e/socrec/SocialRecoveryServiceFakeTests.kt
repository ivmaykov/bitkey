package build.wallet.f8e.socrec

import build.wallet.auth.AuthTokenScope
import build.wallet.bitcoin.BitcoinNetworkType.BITCOIN
import build.wallet.bitkey.account.FullAccount
import build.wallet.bitkey.account.FullAccountConfig
import build.wallet.bitkey.f8e.FullAccountId
import build.wallet.bitkey.keybox.KeyboxMock
import build.wallet.bitkey.socrec.TrustedContactAlias
import build.wallet.f8e.F8eEnvironment.Development
import build.wallet.f8e.auth.HwFactorProofOfPossession
import build.wallet.ktor.result.HttpError.UnhandledException
import build.wallet.platform.random.UuidFake
import build.wallet.testing.shouldBeErrOfType
import build.wallet.testing.shouldBeOk
import build.wallet.time.ClockFake
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration.Companion.days

class SocialRecoveryServiceFakeTests : FunSpec({
  val uuid = UuidFake()
  val clock = ClockFake()

  val serviceFake =
    SocialRecoveryServiceFake(
      uuid = uuid,
      backgroundScope = TestScope(),
      clock = clock
    )

  val fullAccount1 =
    FullAccount(
      accountId = FullAccountId("1"),
      config =
        FullAccountConfig(
          bitcoinNetworkType = BITCOIN,
          f8eEnvironment = Development,
          isTestAccount = false,
          isUsingSocRecFakes = false,
          isHardwareFake = false
        ),
      keybox = KeyboxMock
    )
  val fullAccount2 =
    FullAccount(
      accountId = FullAccountId("2"),
      config =
        FullAccountConfig(
          bitcoinNetworkType = BITCOIN,
          f8eEnvironment = Development,
          isTestAccount = false,
          isUsingSocRecFakes = false,
          isHardwareFake = false
        ),
      keybox = KeyboxMock
    )

  val hwPopMock = HwFactorProofOfPossession("")

  beforeTest {
    uuid.reset()
    serviceFake.reset()
  }

  test("create invitations") {
    // Create first invitation
    serviceFake
      .createInvitation(
        account = fullAccount2,
        hardwareProofOfPossession = hwPopMock,
        trustedContactAlias = TrustedContactAlias("Jack")
      )
      .shouldBeOk {
        it.recoveryRelationshipId.shouldBe("uuid-0")
        it.trustedContactAlias.shouldBe(TrustedContactAlias("Jack"))
      }

    // Relationships should contain first invitation
    serviceFake.getRelationships(
      accountId = fullAccount2.accountId,
      f8eEnvironment = Development,
      hardwareProofOfPossession = null
    ).shouldBeOk { relationships ->
      relationships.invitations.single().should {
        it.recoveryRelationshipId.shouldBe("uuid-0")
        it.trustedContactAlias.shouldBe(TrustedContactAlias("Jack"))
      }
      relationships.protectedCustomers.shouldBeEmpty()
      relationships.trustedContacts.shouldBeEmpty()
    }

    // Create a second invitation
    serviceFake
      .createInvitation(
        account = fullAccount2,
        hardwareProofOfPossession = hwPopMock,
        trustedContactAlias = TrustedContactAlias("Kirill")
      )
      .shouldBeOk {
        it.recoveryRelationshipId.shouldBe("uuid-1")
        it.trustedContactAlias.shouldBe(TrustedContactAlias("Kirill"))
      }

    // Relationships should contain first and second invitations
    serviceFake.getRelationships(
      accountId = fullAccount1.accountId,
      f8eEnvironment = Development,
      hardwareProofOfPossession = null
    ).shouldBeOk { relationships ->
      relationships.invitations.shouldHaveSize(2)
      relationships.invitations[0].should {
        it.recoveryRelationshipId.shouldBe("uuid-0")
        it.trustedContactAlias.shouldBe(TrustedContactAlias("Jack"))
      }

      relationships.invitations[1].should {
        it.recoveryRelationshipId.shouldBe("uuid-1")
        it.trustedContactAlias.shouldBe(TrustedContactAlias("Kirill"))
      }
    }
  }

  test("remove invitation") {
    // Create an invitation
    serviceFake
      .createInvitation(
        account = fullAccount1,
        hardwareProofOfPossession = hwPopMock,
        trustedContactAlias = TrustedContactAlias("Jack")
      )
      .shouldBeOk {
        it.recoveryRelationshipId.shouldBe("uuid-0")
        it.trustedContactAlias.shouldBe(TrustedContactAlias("Jack"))
      }

    // Relationships should contain the invitation
    serviceFake.getRelationships(
      accountId = fullAccount1.accountId,
      f8eEnvironment = Development,
      hardwareProofOfPossession = null
    ).shouldBeOk { relationships ->
      relationships.invitations.single().should {
        it.recoveryRelationshipId.shouldBe("uuid-0")
        it.trustedContactAlias.shouldBe(TrustedContactAlias("Jack"))
      }
      relationships.protectedCustomers.shouldBeEmpty()
      relationships.trustedContacts.shouldBeEmpty()
    }

    // Remove the invitation
    serviceFake.removeRelationship(
      account = fullAccount1,
      hardwareProofOfPossession = hwPopMock,
      authTokenScope = AuthTokenScope.Global,
      relationshipId = "uuid-0"
    ).shouldBeOk()

    // Relationships should no longer have the invitation
    serviceFake.getRelationships(
      accountId = fullAccount1.accountId,
      f8eEnvironment = Development,
      hardwareProofOfPossession = null
    ).shouldBeOk { relationships ->
      relationships.invitations.shouldBeEmpty()
      relationships.protectedCustomers.shouldBeEmpty()
      relationships.trustedContacts.shouldBeEmpty()
    }
  }

  test("cannot remove relationship that doesn't exist") {
    // Create an invitation
    serviceFake
      .createInvitation(
        account = fullAccount1,
        hardwareProofOfPossession = hwPopMock,
        trustedContactAlias = TrustedContactAlias("Jack")
      )
      .shouldBeOk {
        it.recoveryRelationshipId.shouldBe("uuid-0")
        it.trustedContactAlias.shouldBe(TrustedContactAlias("Jack"))
      }

    // Relationships should contain the invitation
    serviceFake.getRelationships(
      accountId = fullAccount1.accountId,
      f8eEnvironment = Development,
      hardwareProofOfPossession = null
    ).shouldBeOk { relationships ->
      relationships.invitations.single().should {
        it.recoveryRelationshipId.shouldBe("uuid-0")
        it.trustedContactAlias.shouldBe(TrustedContactAlias("Jack"))
      }
      relationships.protectedCustomers.shouldBeEmpty()
      relationships.trustedContacts.shouldBeEmpty()
    }

    // Attempt to remove a nonexistent invitation - error
    serviceFake.removeRelationship(
      account = fullAccount1,
      hardwareProofOfPossession = hwPopMock,
      authTokenScope = AuthTokenScope.Global,
      relationshipId = "uuid-1"
    ).shouldBeErrOfType<UnhandledException>().cause.message.shouldBe(
      "Relationship uuid-1 not found."
    )

    // Relationships still contain the initial invitation
    serviceFake.getRelationships(
      accountId = fullAccount1.accountId,
      f8eEnvironment = Development,
      hardwareProofOfPossession = null
    ).shouldBeOk { relationships ->
      relationships.invitations.single().should {
        it.recoveryRelationshipId.shouldBe("uuid-0")
        it.trustedContactAlias.shouldBe(TrustedContactAlias("Jack"))
      }
      relationships.protectedCustomers.shouldBeEmpty()
      relationships.trustedContacts.shouldBeEmpty()
    }
  }

  test("reinvite") {
    // Create first invitation
    serviceFake
      .createInvitation(
        account = fullAccount2,
        hardwareProofOfPossession = hwPopMock,
        trustedContactAlias = TrustedContactAlias("Jack")
      )
      .shouldBeOk {
        it.recoveryRelationshipId.shouldBe("uuid-0")
        it.trustedContactAlias.shouldBe(TrustedContactAlias("Jack"))
      }

    clock.advanceBy(8.days)

    // invitation should be expired
    serviceFake.getRelationships(
      accountId = fullAccount1.accountId,
      f8eEnvironment = Development,
      hardwareProofOfPossession = null
    ).shouldBeOk { relationships ->
      relationships.invitations.single().should {
        it.isExpired(clock).shouldBe(true)
      }
    }

    // refresh invite
    serviceFake.refreshInvitation(
      account = fullAccount2,
      hardwareProofOfPossession = hwPopMock,
      relationshipId = "uuid-0"
    ).shouldBeOk {
      it.recoveryRelationshipId.shouldBe("uuid-0")
      it.trustedContactAlias.shouldBe(TrustedContactAlias("Jack"))
      it.isExpired(clock).shouldBe(false)
    }

    // Relationships should contain refreshed invitation
    serviceFake.getRelationships(
      accountId = fullAccount2.accountId,
      f8eEnvironment = Development,
      hardwareProofOfPossession = null
    ).shouldBeOk { relationships ->
      relationships.invitations.single().should {
        it.recoveryRelationshipId.shouldBe("uuid-0")
        it.trustedContactAlias.shouldBe(TrustedContactAlias("Jack"))
        it.isExpired(clock).shouldBe(false)
      }
      relationships.protectedCustomers.shouldBeEmpty()
      relationships.trustedContacts.shouldBeEmpty()
    }
  }

  test("can't reinvite non-existing relationship") {
    // Create first invitation
    serviceFake
      .createInvitation(
        account = fullAccount2,
        hardwareProofOfPossession = hwPopMock,
        trustedContactAlias = TrustedContactAlias("Jack")
      )
      .shouldBeOk {
        it.recoveryRelationshipId.shouldBe("uuid-0")
        it.trustedContactAlias.shouldBe(TrustedContactAlias("Jack"))
      }

    serviceFake.refreshInvitation(
      account = fullAccount2,
      hardwareProofOfPossession = hwPopMock,
      relationshipId = "uuid-123123123123"
    ).shouldBeErrOfType<UnhandledException>().cause.message.shouldBe(
      "Invitation uuid-123123123123 not found."
    )
  }
})
