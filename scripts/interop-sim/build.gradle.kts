// Compiles ONLY the pure-JVM multicast codec sources straight from app/src and
// drives them from harness/Harness.kt. If one of these files ever grows an
// Android dependency this build fails loudly, which is the point.
plugins {
    kotlin("jvm") version "2.4.20"
    application
}

repositories { mavenCentral() }

dependencies {
    // android.jar ships org.json; on a plain JVM we need the standalone one.
    implementation("org.json:json:20231013")
}

val codecDir = "../../app/src/main/java/com/atakmap/android/xv/transport/multicast"

sourceSets.main {
    kotlin.srcDir("harness")
    kotlin.srcDir(codecDir)
    kotlin.include(
        "Harness.kt",
        "RtpFraming.kt",
        "OpusPacking.kt",
        "MulticastWireCodec.kt",
        "ChannelMulticastConfig.kt",
        "ControlPacket.kt",
        "AeadCodec.kt",
        "ChannelKeyRegistry.kt",
        "MulticastGroupDerivation.kt",
        "ServerIdentity.kt",
    )
}

application { mainClass.set("HarnessKt") }
