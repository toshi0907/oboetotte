# Kotlin/AGP/Composeコンパイラプラグイン

Kotlin 2.0.21 + AGP 8.7.3。**Composeを使うには別途`org.jetbrains.kotlin.plugin.compose`というGradleプラグインが必須**です(ルートの`build.gradle.kts`と`app/build.gradle.kts`の両方に適用済み)。Kotlin 2.0以降、従来の`composeOptions.kotlinCompilerExtensionVersion`による指定方法は機能せず、指定しないと「Compose Compiler Gradle plugin is required」というエラーでビルドが失敗します。Kotlinのバージョンを上げる際は、この2箇所のプラグイン指定を同期させてください。
