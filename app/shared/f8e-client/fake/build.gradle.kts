import build.wallet.gradle.logic.extensions.targets

plugins {
  id("build.wallet.kmp")
}

kotlin {
  targets(ios = true, jvm = true)

  sourceSets {
    commonMain {
      dependencies {
        api(projects.shared.analyticsPublic)
        api(projects.shared.bitkeyPrimitivesFake)
        api(projects.shared.ktorTestFake)
        api(projects.shared.notificationsFake)
        api(projects.shared.timeFake)
      }
    }
  }
}
