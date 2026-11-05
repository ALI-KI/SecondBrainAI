# رفع نسخة v0.3.1 إلى GitHub — بدون تغيير مفتاح API

المستودع الحالي: https://github.com/ALI-KI/SecondBrainAI

## الطريقة المضمونة باستخدام Git (مستحسنة)

1. أنشئ نسخة ZIP احتياطية من بيانات التطبيق المثبت قبل أي محاولة تحديث.
2. فك ملف `SecondBrainAI_v0.3.1_FinalFix_GitHub_Source.zip` في مجلد منفصل.
3. افتح نسخة مستودع Git المحلية (Clone) ثم انسخ **محتويات** المجلد المفكوك إليها، مع استبدال الملفات المتطابقة.
4. احذف `.github/workflows/android-apk.yml` من المستودع لو كان موجودًا، واحتفظ بـ `.github/workflows/build-apk.yml` فقط.
5. من جذر المستودع شغّل:

```bash
git add -A
git commit -m "Fix voice rotation, reminders, attachment migration and app lock (v0.3.1)"
git push origin main
```

6. افتح Actions → Build Android APK؛ سيتم تشغيل اختبارات الوحدة وبناء Debug APK ثم رفعه في Artifacts.

لو هترفع من واجهة GitHub في المتصفح، تأكد أن الملفات توضع في الجذر (`app`, `build.gradle.kts`, `.github/workflows/build-apk.yml`) **وليس داخل مجلد إضافي باسم النسخة**، وأن ملف `.github` لم يُخفِه نظام التشغيل.

**مهم:** APK من Runner مختلف قد يكون موقّعًا بمفتاح مختلف. لا تحذف نسخة بها بيانات مهمة عند فشل التحديث؛ الاستعادة من ZIP غير مبرمجة حتى الآن. لتحديثات مستقبلية متتابعة، استخدم شهادة توقيع ثابتة.
