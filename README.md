# WalkieTalkieIntercom
# WalkieTalkieIntercom

Aplikasi interkom Android dengan komunikasi audio lokal/Internet, room, PTT/VOX, relay WebSocket, sistem lisensi, dan web admin backend.

## Status keamanan repository

Repository ini adalah template/source publik. Credential server, file lisensi operasional, data pelanggan, binding perangkat, backup, dan signing key sengaja tidak disertakan.

## Persyaratan

- Android Studio dengan Android SDK dan JDK yang sesuai dengan Gradle project.
- Node.js yang mendukung runtime backend.
- `npm install` di folder `server` untuk memasang dependency dari `package-lock.json`.
- Server HTTPS/WSS atau reverse proxy TLS untuk deployment Internet.

## Membuka project Android

1. Buka folder project ini di Android Studio.
2. Tunggu Gradle sync selesai.
3. Gunakan konfigurasi Android privat untuk endpoint dan credential release.
4. Jangan memasukkan credential produksi ke source atau commit Git.

## Menjalankan backend

1. Salin `server/.env.example` ke konfigurasi environment privat pada mesin server.
2. Isi `ADMIN_API_TOKEN`, `RELAY_TOKEN`, `SESSION_SECRET`, `ADMIN_ORIGIN`, dan `LICENSE_DATA_PATH` secara privat.
3. Letakkan salinan operasional `licenses.json` di lokasi privat yang ditunjuk `LICENSE_DATA_PATH`.
4. Pastikan file `server/security.json` dan data lisensi produksi tidak berasal dari repository publik.
5. Jalankan `node admin-server.js` dari folder `server` setelah environment tersedia.

## Konfigurasi privat wajib

Backend tidak dapat digunakan penuh tanpa credential runtime, path data lisensi privat, dan konfigurasi HTTPS/WSS. Credential admin/server tidak disediakan dalam repository ini.

Client Android pada source publik juga tidak memuat credential admin/server. Build release memerlukan konfigurasi privat yang dikelola di luar repository publik.

## Fitur yang memerlukan konfigurasi server

Aktivasi dan pemeriksaan lisensi, pengambilan konfigurasi relay, koneksi relay Internet, operasi web admin, dan penyimpanan lisensi tidak dapat berjalan tanpa konfigurasi autentikasi server yang valid. Pemeriksaan lisensi tetap aktif dan tidak dinonaktifkan.

## Lisensi

Dependency dan source mengikuti file yang ada di repository. Data lisensi operasional dan identitas pelanggan harus disediakan melalui penyimpanan privat deployment.
