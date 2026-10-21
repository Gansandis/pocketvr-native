# PocketVR Native (APK)

เปลือกแอป Android ที่ห่อ PocketVR ไว้ใน WebView พร้อมสะพานเชื่อมให้เว็บ:
- แสดงรายชื่อแอปที่ติดตั้งในเครื่อง (พร้อมไอคอน) ในแท็บ "แอปในเครื่อง"
- สั่งเปิดแอปได้ตรงๆ ด้วยมือ (ไม่ต้องแตะจออีกครั้ง)
- ขอสิทธิ์กล้องครั้งเดียว ไม่ถามซ้ำทุกครั้งที่เปิด
- เลือกไฟล์วิดีโอ/ภาพ 360° จากเครื่องได้
- ปุ่มย้อนกลับของ Android ปิดเบราว์เซอร์/ออกจากแอปย่อยก่อน แล้วค่อยปิดแอป

## ขั้นตอนบนมือถือ (Termux)
```
pkg update -y && pkg install -y git gh unzip
termux-setup-storage
git config --global user.name "ชื่อคุณ"
git config --global user.email "อีเมลคุณ"
cd ~ && unzip -o ~/storage/downloads/pocketvr_native.zip -d pocketvr_native && cd pocketvr_native
git init -b main && git add -A && git commit -m "PocketVR native"
gh auth login --scopes workflow
gh repo create pocketvr-native --public --source=. --push
```
(ตอน `gh auth login` เลือก GitHub.com → HTTPS → Y → Login with a web browser แล้วเอาโค้ดไปกรอกที่ github.com/login/device)

## ดูผลการสร้างแอป
```
gh run watch
```
เมื่อเสร็จ ไฟล์ติดตั้งจะอยู่ที่ github.com/<ชื่อผู้ใช้>/pocketvr-native/releases → PocketVR.apk

ถ้าสร้างไม่ผ่าน: `gh run view --log-failed | tail -60` แล้วส่งข้อความมาให้ดู

## อัปเดตเว็บในแอป
แก้ไฟล์ `app/src/main/assets/index.html` แล้ว
```
git add -A && git commit -m update && git push
```
GitHub จะสร้าง APK ใหม่ให้เอง (ลายเซ็นเหมือนเดิม ติดตั้งทับได้)
