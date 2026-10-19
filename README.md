# فرسان لان — مراسلة P2P محلية

تطبيق مراسلة عربي RTL. يعمل كل جهاز كنظير مستقل؛ يكتشف Windows أقرانه على شبكة Wi‑Fi المحلية، ويمكن ربط Windows وAndroid أو هاتفين Android مباشرةً عبر Bluetooth Classic بعد الاقتران. لا توجد خدمة سحابية أو خادم مركزي يرحّل محتوى الرسائل أو الملفات. إصدار Windows الحالي `0.5.0` وإصدار Android `0.2.0`.

> هذا تنفيذ مستقل لأفكار المراسلة المحلية؛ لم تُنسخ شيفرة LAN Messenger المرخّصة GPL-3.0. ترخيص هذا المشروع MIT.

## طرق الاتصال

1. **Wi‑Fi:** يفتح عميل Windows نقطة نظير محلية على TCP `43821` ويعلن عبر UDP broadcast على `43820`. يكتشف Android أقران Windows تلقائيًا على الشبكة نفسها. الرسائل والملفات تنتقل إلى النظير المستهدف عبر وصلة مباشرة.
2. **Bluetooth Classic/RFCOMM:** يقترن المستخدم بالجهاز الآخر من إعدادات النظام. يستمع Android لاتصالات RFCOMM ويستطيع بدء اتصال بجهاز مقترن؛ يتصل Windows عبر منفذ Bluetooth SPP/COM الصادر. لا يحتاج النقل إلى عنوان IP أو خادم وسيط.
3. **مشاركة الملفات:** ترسل الملفات على شكل أجزاء 32 كيلوبايت، ويُحفظ محتوى Bluetooth محليًا في IndexedDB على Android بدل الاحتفاظ بالملف كاملًا في ذاكرة التطبيق. حد الملف الحالي 10 ميغابايت.
4. **الصوت والكاميرا:** مكالمات WebRTC المباشرة متاحة عبر Wi‑Fi/IP فقط. Bluetooth Classic في هذا الإصدار مخصص للرسائل والملفات، وليس ناقلًا لصوت/فيديو أو إشارة WebRTC.

## الميزات

- اكتشاف Windows تلقائيًا عبر UDP broadcast في الشبكة المحلية.
- دردشة عامة وخاصة ومجموعات وسجل محلي وإيصالات التسليم/القراءة.
- اتصال Bluetooth Classic مباشر بين Android وWindows، وبين هاتفين Android مقترنين.
- مشاركة ملفات P2P مجزأة، مع تقدم النقل وإيقاف/استئناف/إلغاء؛ حد الملف 10 ميغابايت.
- Android يحتفظ بملفات Bluetooth في IndexedDB وينزّلها إلى الهاتف؛ ملفات Wi‑Fi تُحمّل من النظير الذي يخزنها.
- مكالمات صوتية ومرئية WebRTC عبر Wi‑Fi مع التحكم بالميكروفون والكاميرا.
- واجهة عربية RTL متجاوبة لشاشات الهواتف، ولا يعتمد Bluetooth على اتصال إنترنت.

## استخدام Bluetooth

### Android ↔ Android

1. فعّل Bluetooth على الهاتفين، واجعلهما قابلين للاكتشاف مؤقتًا عند الحاجة، ثم أجرِ الاقتران من إعدادات Android. يجب أن يكون Bluetooth Classic مدعومًا على الجهازين.
2. افتح «فرسان لان» على الهاتفين، اضغط **بلوتوث**، واسمح بإذن «الأجهزة القريبة» عند الطلب. يبدأ كل هاتف مستمع RFCOMM.
3. على أحد الهاتفين اختر الجهاز المقترن من القائمة واضغط **اتصال**. بعد ظهور حالة الاتصال، يمكن بدء محادثة مباشرة ومشاركة ملف.

### Android ↔ Windows

1. اقترن الهاتف والكمبيوتر من إعدادات Bluetooth في النظام. على Android افتح لوحة Bluetooth في فرسان لان حتى يبدأ مستمع RFCOMM.
2. في Windows، أنشئ **Outgoing COM port** لخدمة Serial Port Profile (SPP) الخاصة بالهاتف من إعدادات Bluetooth/COM Ports. تتطلب بعض إصدارات Windows تفعيل خدمة Serial Port على الهاتف أولًا.
3. في فرسان لان على Windows افتح لوحة Bluetooth، حدّث المنافذ، اختر منفذ COM الصادر واضغط **اتصال**. ابدأ أيضًا الاقتران/المستمع على Android قبل الاتصال.
4. النقل عبر Bluetooth لا يفتح تلقائيًا عند بدء التطبيق: الاقتران واختيار COM/الجهاز إجراء صريح من المستخدم. إذا انقطع الرابط، أعد الاتصال من اللوحة.

## تشغيل Android على Wi‑Fi

1. وصّل الهاتف وأجهزة Windows إلى شبكة Wi‑Fi محلية واحدة، مع السماح بحركة الأجهزة بينها وإيقاف عزل عملاء Wi‑Fi.
2. اسمح لـ«فرسان لان» في Windows Firewall ضمن الشبكات الخاصة. يستخدم Windows TCP `43821` للاتصالات وUDP `43820` للاكتشاف، إضافة إلى حركة UDP الخاصة بـWebRTC.
3. ثبّت APK وافتح التطبيق؛ سيبحث عن أقران Windows تلقائيًا. عند أول مكالمة سيطلب Android إذني الميكروفون و/أو الكاميرا حسب نوع المكالمة.
4. يتطلب Android 8.0 (API 26) أو أحدث. APK الاختبار موقّع بمفتاح Debug للتثبيت الجانبي، وليس إصدار متجر Play. قد تحتاج إلى السماح لمتصفح الهاتف بتثبيت التطبيقات غير المعروفة.

## البناء

### Android

يعيد Gradle استخدام واجهة `src/` وSocket.IO من المشروع نفسه. يتطلب بناء APK Android SDK Platform 36 وBuild Tools 36.0.0.

```bash
npm ci
cd android
./gradlew testDebugUnitTest assembleDebug
```

يشغّل CI على **JDK 26**، أحدث إصدار Java تم اختياره لبيئة البناء، مع Gradle `9.6.0` وAndroid Gradle Plugin `9.4.0`. يستخدم كود التطبيق bytecode Java `17`؛ الهاتف لا يثبّت JDK 26، بل يشغّل التطبيق على Android Runtime (ART). يرفع سير عمل **Build Android APK** ملف `Fursan-LAN-Android-0.2.0.apk` كـartifact لمدة 30 يومًا.

### Windows

يتطلب بناء Windows Node.js `22.12` أو أحدث؛ ملف Windows النهائي لا يحتاج إلى تثبيت Node.js.

```bash
npm ci
npm test
npm run build:win
npm run build:portable
```

ينتج مثبت NSIS ونسخة portable باسمين مستقلين داخل `release/`. يُضمّن Windows دعم Bluetooth عبر منفذ SPP/COM باستخدام serialport.

## الحدود والأمان

- Bluetooth يحتاج **Bluetooth Classic/RFCOMM**، واقترانًا مسبقًا. لا يستخدم BLE، ولا ينشئ اقتران النظام نيابة عن المستخدم. يتطلب Windows منفذ COM صادرًا متوافقًا؛ توفره يختلف حسب محول Bluetooth والتعريفات.
- Android-to-Android عبر Wi‑Fi لا يزال غير مدعوم؛ مسار Android-to-Android المضاف هنا هو Bluetooth فقط.
- مكالمات الصوت/الفيديو لا تعمل عبر Bluetooth؛ يلزم مسار IP مباشر لاستخدام WebRTC.
- الشبكات الفرعية المختلفة أو الإنترنت/VPN غير مختبرة؛ الاكتشاف يعتمد UDP broadcast. قد تمنع سياسات العزل أو جدار الحماية اتصال Wi‑Fi.
- لا توجد مصادقة تشفيرية على مستوى التطبيق أو تشفير طرفي مستقل. اقتران Bluetooth قد يوفّر حماية وصلة النظام، لكنه ليس بديلًا عن E2EE ومصادقة هوية الأقران. استخدم التطبيق على أجهزة وشبكات موثوقة فقط، ولا تتبادل معلومات شديدة الحساسية.
- اختبارات CI والوحدات لا تثبت التوافق مع كل هاتف أو محول/تعريف Windows. يلزم اختبار عتادي فعلي قبل وصف التوافق بأنه شامل. ملفات Windows غير موقعة رقميًا وAPK Debug غير مخصص للنشر التجاري.

## Java ومراجع التوافق

- [Oracle: إصدار Java 26](https://www.oracle.com/news/announcement/oracle-releases-java-26-2026-03-17/)
- [Android: توافق Android Gradle Plugin 9.4](https://developer.android.com/build/releases/agp-9-4-0-release-notes)
- [Gradle: مصفوفة توافق JVM](https://docs.gradle.org/current/userguide/compatibility.html)
- [Android: إعدادات JDK وJava للـbuild](https://developer.android.com/build/jdks)
- [Android: Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Android: RFCOMM connect](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices)
- [Android: نقل البيانات عبر BluetoothSocket](https://developer.android.com/develop/connectivity/bluetooth/transfer-data)
- [Microsoft: Bluetooth RFCOMM على Windows](https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/send-or-receive-files-with-rfcomm)

## الاختبارات

`npm test` يشمل اختبارات مخزن الرسائل والملفات والخصوصية، وبروتوكول RFCOMM، وعميل Bluetooth، وموصل COM، ومخزن IndexedDB. يختبر `./gradlew testDebugUnitTest` ترميز إطارات Java واكتشاف الأقران وسياسة عناوين LAN. اختبار البروتوكول لا يحل محل الاقتران والنقل بين أجهزة حقيقية؛ يلزم اختبار هاتف Android فعلي وWindows مع SPP/COM للتحقق من خصائص المحول وسرعة النقل واستقرار الاتصال.
