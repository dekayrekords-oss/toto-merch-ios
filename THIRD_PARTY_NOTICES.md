# Third-party dependencies

The root MIT license covers Toto Merch contributions. Third-party software and model downloads retain their upstream terms.

- The bundled bzip2 source retains its [original license](ios-shared/iosApp/BZip2/LICENSE).
- The Gradle wrapper is part of [Gradle](https://github.com/gradle/gradle/blob/master/LICENSE). Kotlin, Jetpack Compose, Compose Multiplatform, Ktor, kotlinx.coroutines, and kotlinx.serialization are declared in the Gradle build; their distributions contain their upstream notices.
- [llama.cpp](https://github.com/ggml-org/llama.cpp/tree/b11163), [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8), and the [Needle engine](https://huggingface.co/Cactus-Compute/needle3) are fetched by the native setup scripts, not bundled in this source release.
- The app fetches its [Qwen3 GGUF model](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF) separately. Optional speech or model downloads retain the terms at their upstream locations. Model files are not included.
- The Node.js package manifest and lockfile identify Express, qrcode, and their dependencies. These packages are installed separately.
