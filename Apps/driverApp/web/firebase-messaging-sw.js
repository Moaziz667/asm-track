importScripts("https://www.gstatic.com/firebasejs/8.10.0/firebase-app.js");
importScripts("https://www.gstatic.com/firebasejs/8.10.0/firebase-messaging.js");

firebase.initializeApp({
  apiKey: "AIzaSyATOeyHWwOO3YRuGXDBJjsCmUeC08iCOgY",
  appId: "1:603158003434:web:e000000000000000000000",
  messagingSenderId: "603158003434",
  projectId: "driverapp-e7b37",
  authDomain: "driverapp-e7b37.firebaseapp.com",
  storageBucket: "driverapp-e7b37.firebasestorage.app",
});

const messaging = firebase.messaging();

messaging.onBackgroundMessage((payload) => {
  console.log('[firebase-messaging-sw.js] Received background message ', payload);
  const notificationTitle = payload.notification?.title
    ?? payload.data?.event_type
    ?? 'Notification';
  const notificationOptions = {
    body: payload.notification?.body
      ?? (payload.data?.event_type ? 'Événement: ' + payload.data.event_type : 'Vous avez une nouvelle notification'),
    icon: '/icons/Icon-192.png'
  };

  self.registration.showNotification(notificationTitle, notificationOptions);
});
