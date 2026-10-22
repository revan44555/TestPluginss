import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream:gradle:81b1d424d2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(
    configuration: CloudstreamExtension.() -> Unit
) = extensions
    .getByName<CloudstreamExtension>("cloudstream")
    .configuration()

fun Project.android(
    configuration: BaseExtension.() -> Unit
) = extensions
    .getByName<BaseExtension>("android")
    .configuration()

// CI'da GITHUB_REPOSITORY "kullanici/depo" bicimindedir; yerel derlemelerde
// yer tutucu kalir (eskiden de oyleydi).
val repoPath = (System.getenv("GITHUB_REPOSITORY") ?: "user/repo")
    .trim().removePrefix("/").removeSuffix("/")
val repoUser = repoPath.substringBefore("/")
val repoName = repoPath.substringAfter("/")

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // DUZELTME: rawLinkFormat icin "builds/" oneki eklendi.
        // Eski setRepo(repoPath) cagrasi GitHub sablonunu
        // ".../%branch%/%filename%" olarak kullaniyordu; buildBranch="master"
        // ile bu ".../master/Filmmakinesi.cs3" uretiyor ama workflow ikilileri
        // builds/ klasorune koydugu icin URL 404 doneyordu.
        setRepo(
            repoUser,
            repoName,
            "https://github.com/$repoUser/$repoName",
            "https://raw.githubusercontent.com/$repoUser/$repoName/%branch%/builds/%filename%"
        )
        // Tek dal (master) kullanimi: indirme URL'leri raw.githubusercontent
        // uzerinden master dalindaki builds/ klasorune isaret etsin.
        buildBranch = "master"
    }

    android {
        // NOT: Her eklenti kendi build.gradle.kts dosyasinda kendi
        // namespace'ini tanimlamalidir. Ortak "com.example" tanimi
        // coklu eklenti derlemesinde paket catismasi yaratiyordu.
        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)

                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations
        val testImplementation by configurations

        // CloudStream
        cloudstream("com.lagradost:cloudstream3:pre-release")

        // CloudStream currently uses NewPipeExtractor 0.26.3 transitively.
        // Pin 0.26.4 for the plugin so the YouTube extractor receives the latest
        // NewPipe fixes available to this project.
        implementation("com.github.teamnewpipe:newpipeextractor:v0.26.4")

        // Kotlin
        implementation(kotlin("stdlib"))

        // HTTP
        implementation(
            "com.github.Blatzar:NiceHttp:0.4.11"
        )

        // HTML Parser
        implementation(
            "org.jsoup:jsoup:1.18.3"
        )

        // JSpecify nullability annotations
        implementation(
            "org.jspecify:jspecify:1.0.0"
        )

        // JSON Parser
        // Do not bump Jackson above 2.13.1
        implementation(
            "com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1"
        )

        // Unit tests
        testImplementation("junit:junit:4.13.1")
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
