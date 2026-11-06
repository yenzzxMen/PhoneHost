# PhoneHost 📱🌐
Apne Android phone se apni site host karo. Server, external API ya cloud ke bina — site phone par hi rehti hai.

## Features (v0.1)
- Embedded web server (NanoHTTPD), foreground service, 24x7 (wake lock + wifi lock + auto-start on boot)
- Files / ZIP upload (poori site replace)
- Termux integration: Python scripts + `cloudflared` tunnel (public URL, apna domain bhi via token)
- Router port-forwarding guide (phone ka IP auto-fill)
- In-app update checker (GitHub Releases)

## APK banane ka tareeka (GitHub Actions)
1. Is folder ko GitHub repo me push karo (repo **public** rakho, update checker ke liye).
2. Repo > Actions tab me "Build APK" automatic chalega. Artifacts me `PhoneHost.apk` milega.
3. Official release ke liye tag lagao:
   ```
   git tag v0.1.0
   git push origin v0.1.0
   ```
   Release page par APK attach ho jayegi.

## Update kaise kare
1. Code badlo, push karo.
2. Naya tag: `git tag v0.1.1 && git push origin v0.1.1`
3. Users app me "Update check karo" dabayenge → naya APK download → install (purane ke upar, data safe).

Ye zaroori hai: `app/phonehost.p12` keystore hamesha same rahe, warna Android "update" install nahi karega.
Hobby ke liye keystore repo me hai. Serious use me ise GitHub Secrets me shift karo.

## Roadmap
- v0.2: UPnP auto port forwarding, tunnel URL dashboard me dikhana
- v0.3: logs, CPU/RAM, HTTPS, password-protected remote upload
- v1.0: multi-phone cluster
