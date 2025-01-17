import build.wallet.gradle.logic.extensions.targets

plugins {
  id("build.wallet.kmp")
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  targets(ios = true, jvm = true)

  sourceSets {
    commonMain {
      dependencies {
        api(projects.shared.accountPublic)
        api(projects.shared.availabilityPublic)
        api(projects.shared.f8eClientPublic)
        api(projects.shared.ktorClientPublic)
        api(projects.shared.databasePublic)
        implementation(projects.shared.loggingPublic)
      }
    }
    commonTest {
      dependencies {
        implementation(projects.shared.amountFake)
        implementation(projects.shared.accountFake)
        implementation(projects.shared.f8eClientFake)
        implementation(projects.shared.f8eFake)
        implementation(projects.shared.featureFlagFake)
        implementation(projects.shared.keyboxFake)
        implementation(projects.shared.moneyFake)
        implementation(projects.shared.platformFake)
        implementation(projects.shared.testingPublic)
        implementation(projects.shared.coroutinesTesting)
        implementation(projects.shared.sqldelightTesting)
      }
    }
  }
}
