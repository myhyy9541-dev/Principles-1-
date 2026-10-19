# Bluetooth Classic — فرسان لان

## التنفيذ

- يستخدم Android RFCOMM مع UUID خدمة Serial Port Profile القياسي؛ يقبل التطبيق جهازًا مقترنًا ويتيح بدء اتصال إلى أجهزة مقترنة من واجهة فرسان لان. لا ينفذ التطبيق الاقتران أو المسح نيابةً عن المستخدم.
- يستخدم Windows منافذ Bluetooth SPP/COM الصادرة من خلال `serialport` داخل Electron. يختار المستخدم المنفذ بعد إقران الجهاز في إعدادات Windows؛ لا ينشئ التطبيق COM port أو خدمة النظام تلقائيًا.
- يحدد `src/bluetooth-protocol.js` و`BluetoothFrameCodec.java` framing بطول 32-bit big-endian وحد أقصى 64 KiB للإطار. أجزاء الملفات 32 KiB وتُكتب على Android إلى IndexedDB؛ الحد الحالي 10 MiB للملف.
- يمرّ كل جهاز إلى نقطة النظير المحلية الخاصة بالتطبيق؛ لا يُعاد بث محتوى وارد من نظير Bluetooth إلى أجهزة أخرى. المكالمات WebRTC والصوت/الفيديو ليست جزءًا من نقل RFCOMM.

## التوافق والأذونات

- على Android 12 فما فوق يطلب التطبيق `BLUETOOTH_CONNECT` وقت التشغيل للتعامل مع الأجهزة المقترنة وRFCOMM. المصدر: [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).
- اتصال RFCOMM يحتاج أن يعمل أحد الطرفين كخادم `BluetoothServerSocket` والآخر كعميل، وأن يتطابق UUID الخاص بالخدمة. الاقتران غير المكتمل قد يوقف الاتصال إلى أن يوافق المستخدم. المصدر: [Connect Bluetooth devices](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices).
- نقل RFCOMM يعتمد `InputStream` و`OutputStream`; القراءة والكتابة حجبية وتعمل على عمال I/O مع طوابير محدودة وإغلاق للمقابس. المصدر: [Transfer Bluetooth data](https://developer.android.com/develop/connectivity/bluetooth/transfer-data).
- مسار Windows المحدد هو SPP/COM، وليس WinRT RFCOMM أو BLE. نجاح تشغيل المنفذ يعتمد على تعريفات ومحول Windows وخدمة SPP التي يوفرها النظام/الجهاز.

## الاختبار والحدود

- اختبارات الوحدة تغطي codec/framing، التدفق المجزأ، acknowledgements، التخزين المجزأ في IndexedDB، والموصل المنطقي لمنفذ COM.
- لا تختبر المحاكاة سلوك الاقتران أو توافق أجهزة Bluetooth الفعلية أو سرعات النقل. يجب إكمال smoke test على Android ↔ Android وAndroid ↔ Windows مع COM صادر قبل وصف التوافق بأنه شامل.
- القناة مخصصة للنص والملفات فقط. نقل صوت/فيديو عبر Bluetooth يتطلب تصميمًا وتجارب عتادية منفصلة؛ يستخدم WebRTC عبر Wi‑Fi/IP حيث يتوفر اتصال شبكي مباشر.
- Bluetooth pairing يوفر حماية طبقة النظام وفق إعدادات الجهاز، لكنه لا ينشئ مصادقة تشفيرية أو E2EE خاصة بالتطبيق.
