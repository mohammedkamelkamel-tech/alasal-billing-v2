# بناء APK عبر GitHub Actions

هذه النسخة تستخدم:
- JDK 17
- Gradle 9.3.1
- AGP 9.1.1
- Built-in Kotlin في AGP 9.x

لا يتم إنشاء Gradle Wrapper أثناء الـWorkflow. يتم تثبيت Gradle 9.3.1 مباشرة بواسطة `gradle/actions/setup-gradle`.

Workflow:
`.github/workflows/android.yml`

المهمة:
`gradle :app:assembleDebug`

الناتج:
`app/build/outputs/apk/debug/*.apk`
