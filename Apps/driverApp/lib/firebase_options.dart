import 'package:firebase_core/firebase_core.dart' show FirebaseOptions;
import 'package:flutter/foundation.dart' show defaultTargetPlatform, kIsWeb, TargetPlatform;

class DefaultFirebaseOptions {
  static FirebaseOptions get currentPlatform {
    if (kIsWeb) {
      return web;
    }
    switch (defaultTargetPlatform) {
      case TargetPlatform.android:
        return android;
      case TargetPlatform.iOS:
        return ios;
      default:
        throw UnsupportedError(
          'DefaultFirebaseOptions are not configured for this platform.',
        );
    }
  }

  static const FirebaseOptions web = FirebaseOptions(
    apiKey: 'AIzaSyATOeyHWwOO3YRuGXDBJjsCmUeC08iCOgY',
    appId: '1:603158003434:web:e000000000000000000000',
    messagingSenderId: '603158003434',
    projectId: 'driverapp-e7b37',
    authDomain: 'driverapp-e7b37.firebaseapp.com',
    storageBucket: 'driverapp-e7b37.firebasestorage.app',
  );

  static const FirebaseOptions android = FirebaseOptions(
    apiKey: 'AIzaSyATOeyHWwOO3YRuGXDBJjsCmUeC08iCOgY',
    appId: '1:603158003434:android:7491b7627188ec50c62193',
    messagingSenderId: '603158003434',
    projectId: 'driverapp-e7b37',
    storageBucket: 'driverapp-e7b37.firebasestorage.app',
  );

  static const FirebaseOptions ios = FirebaseOptions(
    apiKey: 'AIzaSyDjJqdGYv3oC7fE333jqMjFJdWsABr5msQ',
    appId: '1:603158003434:ios:c99cad77b40490f9c62193',
    messagingSenderId: '603158003434',
    projectId: 'driverapp-e7b37',
    storageBucket: 'driverapp-e7b37.firebasestorage.app',
    iosBundleId: 'com.asm.driverapp',
  );
}
