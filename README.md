# فرسان لان — مراسلة P2P محلية

تطبيق مراسلة عربي RTL. يعمل عميل Windows كنظير مستقل، ويضيف إصدار Android رفيقًا يتصل تلقائيًا بأقران Windows على شبكة Wi‑Fi المحلية. محتوى الرسائل والملفات ينتقل مباشرة بين الجهازين المستهدفين؛ لا توجد خدمة سحابية أو خادم وسيط لإعادة توجيه محتوى الأقران. إصدار Windows الحالي `0.4.1` وإصدار Android الأول `0.1.0`.

> هذا تنفيذ مستقل لأفكار المراسلة المحلية؛ لم تُنسخ شيفرة LAN Messenger المرخّصة GPL-3.0. ترخيص هذا المشروع MIT.

## طريقة الاتصال

1. يفتح كل عميل Windows نقطة اتصال محلية على TCP `43821` ويعلن عن نفسه عبر UDP broadcast على `43820`.
2. يستمع تطبيق Android لإعلانات Windows على الشبكة نفسها، ثم يفتح اتصال Socket.IO مباشرًا بكل نظير Windows مكتشف. لا يحتاج المستخدم إلى إدخال عنوان IP أو إنشاء غرفة.
3. يرسل عميل Android الرسالة أو الملف مباشرة إلى نقطة النظير المقصود. عند إرسال مستخدم Windows إلى هاتف Android متصل، يسلّم جهاز Windows المستقبِل المحتوى عبر الاتصال المباشر نفسه إلى الهاتف المقصود؛ لا يمرّر حمولة وصلت إليه من نظير آخر إلى ثالث.
4. ملفات Android تُحمّل من نقطة الجهاز الذي أرسلها مباشرةً. الصوت والكاميرا يستخدمان WebRTC مع وسائط مباشرة بين الطرفين؛ يقتصر التفاوض على إشارات الاتصال بين هذين الطرفين.

تطبيق Android في هذا الإصدار عميل Wi‑Fi يتصل بأقران Windows المكتشفين، ولا يفتح منفذ استماع خاصًا بالهاتف. لذلك لا يدعم الاتصال المباشر Android‑إلى‑Android بعد، ولا يدعم Bluetooth. هذه نقطة توافق معلنة وليست ادعاءً بوجود mesh كامل على Android.

## الميزات

- اكتشاف تلقائي للأقران داخل الشبكة المحلية عبر UDP broadcast.
- دردشة عامة وخاصة ومجموعات، مع سجل محلي وإيصالات التسليم/القراءة.
- رسائل خاصة مؤجلة لدى نظير Windows المستهدف حتى عودة اتصال المستلم به.
- مشاركة ملفات مباشرة ومجزأة مع تقدم النقل وإيقاف/استئناف/إلغاء؛ حد الملف 10 ميغابايت.
- تنزيل ملفات Android إلى مجلد `Downloads` على الهاتف؛ معاينة الصور.
- مكالمات صوتية ومرئية WebRTC عبر Wi‑Fi مع التحكم بالميكروفون والكاميرا.
- واجهة عربية RTL؛ تصميم متجاوب لشاشات الهواتف.
- لا يتطلب Android Bluetooth أو خدمات سحابية.

## تشغيل Android على Wi‑Fi

1. وصّل الهاتف وأجهزة Windows إلى شبكة Wi‑Fi محلية واحدة، مع السماح بحركة الأجهزة بينها وإيقاف عزل عملاء Wi‑Fi.
2. اسمح لـ«فرسان لان» في Windows Firewall ضمن الشبكات الخاصة. يستخدم Windows TCP `43821` للاتصالات وUDP `43820` للاكتشاف، إضافة إلى حركة UDP الخاصة بـWebRTC.
3. ثبّت APK وافتح التطبيق؛ سيبحث عن أقران Windows تلقائيًا. عند أول مكالمة سيطلب Android إذني الميكروفون و/أو الكاميرا حسب نوع المكالمة. يفتح منتقي Android عند إرفاق ملف.
4. يتطلب التطبيق Android 8.0 (API 26) أو أحدث. أُخرج APK الحالي كحزمة Debug موقعة بمفتاح Debug لتثبيت جانبي واختبار مباشر؛ ليس إصدار متجر Play ولا يتضمن توقيع إصدار خاصًا بالمستخدم. قد تحتاج إلى السماح لمتصفح الهاتف بتثبيت التطبيقات غير المعروفة.

## بناء Android

يعيد Gradle استخدام `src/index.html` و`src/style.css` و`src/renderer.js` وSocket.IO من المشروع نفسه، لتقليل اختلاف السلوك بين Windows وAndroid.

```bash
npm ci
cd android
./gradlew testDebugUnitTest assembleDebug
```

يعمل CI على **JDK 26**، أحدث إصدار Java متاح عند إعداد هذا البناء، مع Gradle `9.6.0` وAndroid Gradle Plugin `9.4.0`. يستخدم كود Android ميزات/bytecode Java `17`، لا Java 26 على الهاتف؛ Android يشغّل التطبيق على ART ولا يثبّت JDK 26. اختير هذا الفصل لتشغيل أدوات البناء الحديثة مع بقاء ناتج Java ضمن مستوى التوافق الموثق لـAndroid.

يشغّل سير عمل **Build Android APK** اختبارات وحدة Java ويُنتج الملف `Fursan-LAN-Android-0.1.0.apk` كـartifact لمدة 30 يومًا. يتطلب البناء المحلي Android SDK Platform 36 وBuild Tools 36.0.0.

## بناء Windows

يتطلب Node.js `22.12` أو أحدث لبناء تطبيق Windows؛ يعمل ملف Windows النهائي دون تثبيت Node.js.

```bash
npm ci
npm test
npm run build:win
npm run build:portable
```

ينشئ `npm run build:win` مثبت NSIS باسم `Fursan-Lan-Setup-0.4.1-x64.exe`، وينشئ `npm run build:portable` نسخة محمولة باسم `Fursan-Lan-Portable-0.4.1-x64.exe` في `release/`. كما يمكن تنزيل artifact من سير عمل **Build Windows installer** في GitHub Actions.

## الحدود والأمان

- **Bluetooth:** غير مدعوم؛ Android يستخدم Wi‑Fi/IP فقط.
- **Android إلى Android:** غير مدعوم بعد؛ Android يتصل بأقران Windows ولا يستضيف نقطة استقبال على الهاتف.
- **الشبكات الفرعية المختلفة أو الإنترنت/VPN:** غير مختبرة؛ الاكتشاف الحالي يعتمد UDP broadcast، والاتصال يحتاج مسار IP مباشرًا بلا NAT.
- **التشفير:** اتصالات Socket.IO/HTTP المحلية غير مشفرة ولا توجد مصادقة تشفيرية موثوقة لهوية الأجهزة. استخدمه على شبكة موثوقة فقط، ولا تتبادل معلومات حساسة قبل إضافة TLS/تشفير طرفي ومصادقة أقران ومراجعة أمنية.
- ملفات Windows غير موقعة رقميًا؛ APK الحالي موقّع بمفتاح Debug للاختبار الجانبي فقط.

## مراجع توافق Java/Android

- [Oracle: إصدار Java 26](https://www.oracle.com/news/announcement/oracle-releases-java-26-2026-03-17/)
- [Android: توافق Android Gradle Plugin 9.4](https://developer.android.com/build/releases/agp-9-4-0-release-notes) — Gradle `9.6.0` وJDK `17` كحد أدنى.
- [Gradle: مصفوفة توافق JVM](https://docs.gradle.org/current/userguide/compatibility.html) — تشغيل Gradle على JDK 26 مدعوم من Gradle `9.4.0` فصاعدًا.
- [Android: إعدادات JDK وJava للـbuild](https://developer.android.com/build/jdks) — يوصي Android بتحديد toolchain، ويشرح اختلاف JDK المستخدم لتشغيل Gradle عن Java source/target في التطبيق.
- [AndroidX WebKit: الإصدارات](https://developer.android.com/jetpack/androidx/releases/webkit) — يستخدم التطبيق الإصدار المستقر `1.17.1` لأداة WebViewAssetLoader.

## الاختبارات

`npm test` يختبر مخزن الرسائل والملفات والمجموعات والخصوصية واستئناف النقل، بما في ذلك حفظ هوية Android ومستلمي الملفات. `./gradlew testDebugUnitTest` يختبر عقد الاكتشاف وسياسة LAN. على Linux يمكن تشغيل `xvfb-run -a node scripts/android-interop-smoke.cjs` للتحقق من رسائل Android ذهابًا وإيابًا، ومنع إعادة التوجيه إلى نظير ثالث، وإشارات المكالمات وبيانات/تنزيل الملفات عبر خادم Windows الفعلي؛ يمثّل الاختبار الهاتف بعميل Socket.IO ولا يحل محل تجربة الهاتف الحقيقية. يلزم اختبار عملي على هاتف Android وجهازي Windows فعليين للتحقق من بث Wi‑Fi وجدار الحماية وجودة WebRTC؛ نجاح بناء CI وحده لا يثبت التوافق على كل طراز هاتف أو شبكة.
