/**
 * Build D — EN/AR copy for onboarding inside the embedded Shopify app
 * (design/Traced_embedded_onboarding_dc.html: O1, S1–S3, L1, C1, X1–X2). Local to the embedded
 * bundle like disconnectedCopy.ts (no shared i18next tree there).
 */
export interface OnboardingCopy {
  pageTitle: string
  fromShopify: string
  // O1
  welcomeTitle: string
  welcomeBody: string
  createTitle: string
  createBody: string
  existingTitle: string
  existingBody: string
  // S1–S3
  signupTitle: string
  signupBody: string
  store: string
  businessName: string
  yourName: string
  mobile: string
  mobilePlaceholder: string
  email: string
  emailHint: string
  password: string
  passwordHint: string
  consentBefore: string
  terms: string
  consentAnd: string
  privacy: string
  back: string
  createAndConnect: string
  errBusinessName: string
  errMobile: string
  errEmail: string
  errPassword: string
  errConsent: string
  errRules: string
  emailTakenTitle: string
  emailTakenBody: string
  signInInstead: string
  useDifferentEmail: string
  // L1
  existingPageTitle: string
  existingPageBody: string
  continueToTraced: string
  // C1
  connectedTitle: string
  connectedBody: string
  stepAccount: string
  stepAccountSub: string
  stepStore: string
  stepStoreSub: string
  stepImport: string
  stepImportSub: string
  stepImportDone: string
  stepImportFailed: string
  stepNext: string
  stepNextSub: string
  openTraced: string
  toDashboard: string
  // X1–X2
  linkedElsewhereTitle: string
  linkedElsewhereBody: string
  signInToTraced: string
  contactSupport: string
  failedTitle: string
  failedBody: string
  tryAgain: string
}

export const onboardingCopy: Record<'en' | 'ar', OnboardingCopy> = {
  en: {
    pageTitle: 'Traced',
    fromShopify: 'From Shopify',
    welcomeTitle: 'Welcome to Traced',
    welcomeBody: "Track every piece from your shelf to your customer's door. Let's connect this store.",
    createTitle: 'Create my Traced account',
    createBody: "Takes a minute. We'll connect this store and start importing your products and orders.",
    existingTitle: 'I already have a Traced account',
    existingBody: 'Sign in and connect this store to it.',
    signupTitle: 'Create your Traced account',
    signupBody: "You'll use this email and password to sign in to Traced on your phone and in the warehouse.",
    store: 'Store',
    businessName: 'Business name',
    yourName: 'Your name',
    mobile: 'Mobile',
    mobilePlaceholder: '010 1234 5678',
    email: 'Email',
    emailHint: 'From your Shopify store. Change it if you sign in with another email.',
    password: 'Password',
    passwordHint: 'At least 8 characters.',
    consentBefore: 'I agree to the',
    terms: 'Terms of Service',
    consentAnd: 'and',
    privacy: 'Privacy Policy',
    back: 'Back',
    createAndConnect: 'Create account and connect',
    errBusinessName: 'Enter your business name.',
    errMobile: 'Enter an Egyptian mobile number, like 010 1234 5678.',
    errEmail: 'Enter a valid email address.',
    errPassword: 'Use at least 8 characters.',
    errConsent: 'Accept the terms to create an account.',
    errRules: 'Check the details above and try again.',
    emailTakenTitle: 'This email is already registered. Sign in instead.',
    emailTakenBody: 'Sign in to connect this store to that account, or use a different email.',
    signInInstead: 'Sign in instead',
    useDifferentEmail: 'Use a different email',
    existingPageTitle: 'Connect this store to your Traced account',
    existingPageBody: "We'll take you to Traced to sign in. Confirm there, and you'll come straight back here.",
    continueToTraced: 'Continue to Traced',
    connectedTitle: 'Your store is connected',
    connectedBody: 'is now linked to Traced.',
    stepAccount: 'Account created',
    stepAccountSub: 'We sent a welcome email to',
    stepStore: 'Store connected',
    stepStoreSub: 'Orders placed from now on come in automatically.',
    stepImport: 'Importing your products',
    stepImportSub: 'This usually takes a few minutes. You can leave this page.',
    stepImportDone: 'Products imported',
    stepImportFailed: "The import didn't finish. Traced will show what happened in Settings › Connections.",
    stepNext: 'Next: connect Bosta and label your stock',
    stepNextSub: 'In Traced, under Settings › Connections.',
    openTraced: 'Open Traced',
    toDashboard: 'Go to the dashboard',
    linkedElsewhereTitle: 'This Shopify store is already connected to a different Traced account. Sign in to that account to manage it.',
    linkedElsewhereBody: 'Sign in to that account, or contact Traced support.',
    signInToTraced: 'Sign in to Traced',
    contactSupport: 'Contact support',
    failedTitle: "We couldn't connect your store.",
    failedBody: 'Nothing was created. Check your connection and try again.',
    tryAgain: 'Try again',
  },
  ar: {
    pageTitle: 'Traced',
    fromShopify: 'من Shopify',
    welcomeTitle: 'مرحبًا بك في Traced',
    welcomeBody: 'تتبّع كل قطعة من الرف حتى باب عميلك. لنربط هذا المتجر.',
    createTitle: 'أنشئ حسابي على Traced',
    createBody: 'يستغرق دقيقة. سنربط هذا المتجر ونبدأ استيراد منتجاتك وطلباتك.',
    existingTitle: 'لديّ حساب على Traced بالفعل',
    existingBody: 'سجّل الدخول واربط هذا المتجر به.',
    signupTitle: 'أنشئ حسابك على Traced',
    signupBody: 'ستستخدم هذا البريد وكلمة المرور لتسجيل الدخول إلى Traced على هاتفك وفي المخزن.',
    store: 'المتجر',
    businessName: 'اسم النشاط التجاري',
    yourName: 'اسمك',
    mobile: 'رقم الموبايل',
    mobilePlaceholder: '010 1234 5678',
    email: 'البريد الإلكتروني',
    emailHint: 'من متجرك على Shopify. غيّره إذا كنت تسجّل الدخول ببريد آخر.',
    password: 'كلمة المرور',
    passwordHint: '8 أحرف على الأقل.',
    consentBefore: 'أوافق على',
    terms: 'شروط الخدمة',
    consentAnd: 'و',
    privacy: 'سياسة الخصوصية',
    back: 'رجوع',
    createAndConnect: 'أنشئ الحساب واربط المتجر',
    errBusinessName: 'أدخل اسم نشاطك التجاري.',
    errMobile: 'أدخل رقم موبايل مصريًا، مثل 010 1234 5678.',
    errEmail: 'أدخل بريدًا إلكترونيًا صحيحًا.',
    errPassword: 'استخدم 8 أحرف على الأقل.',
    errConsent: 'وافق على الشروط لإنشاء حساب.',
    errRules: 'راجع البيانات أعلاه وحاول مرة أخرى.',
    emailTakenTitle: 'هذا البريد الإلكتروني مسجل بالفعل. قم بتسجيل الدخول.',
    emailTakenBody: 'سجّل الدخول لربط هذا المتجر بذلك الحساب، أو استخدم بريدًا آخر.',
    signInInstead: 'سجّل الدخول بدلًا من ذلك',
    useDifferentEmail: 'استخدم بريدًا آخر',
    existingPageTitle: 'اربط هذا المتجر بحسابك على Traced',
    existingPageBody: 'سننقلك إلى Traced لتسجيل الدخول. أكّد هناك وستعود إلى هنا مباشرة.',
    continueToTraced: 'تابع إلى Traced',
    connectedTitle: 'تم ربط متجرك',
    connectedBody: 'أصبح مرتبطًا بـ Traced.',
    stepAccount: 'تم إنشاء الحساب',
    stepAccountSub: 'أرسلنا رسالة ترحيب إلى',
    stepStore: 'تم ربط المتجر',
    stepStoreSub: 'الطلبات الجديدة من الآن تصل تلقائيًا.',
    stepImport: 'جارٍ استيراد منتجاتك',
    stepImportSub: 'يستغرق ذلك عادةً بضع دقائق. يمكنك مغادرة هذه الصفحة.',
    stepImportDone: 'تم استيراد المنتجات',
    stepImportFailed: 'لم يكتمل الاستيراد. سيعرض Traced ما حدث في الإعدادات › الاتصالات.',
    stepNext: 'التالي: اربط Bosta وضع ملصقات على مخزونك',
    stepNextSub: 'في Traced، ضمن الإعدادات › الاتصالات.',
    openTraced: 'افتح Traced',
    toDashboard: 'اذهب إلى لوحة المتابعة',
    linkedElsewhereTitle: 'متجر Shopify هذا مرتبط بالفعل بحساب Traced مختلف. سجّل الدخول إلى ذلك الحساب لإدارته.',
    linkedElsewhereBody: 'سجّل الدخول إلى ذلك الحساب، أو تواصل مع دعم Traced.',
    signInToTraced: 'سجّل الدخول إلى Traced',
    contactSupport: 'تواصل مع الدعم',
    failedTitle: 'تعذّر ربط متجرك.',
    failedBody: 'لم يتم إنشاء أي شيء. تحقق من اتصالك وحاول مرة أخرى.',
    tryAgain: 'حاول مرة أخرى',
  },
}
