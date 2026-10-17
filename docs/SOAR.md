# Soar setup and network selection

WhiteDNS 1.6.6 adds Lantern Soar v0.2.2 alongside StormDNS and CottenDNS.
Soar requires its own server and public key; Master/Cotten server settings
cannot be reused for this engine.

## Server / نصب سرور

Use [WhiteDNS/Soar-Server](https://github.com/WhiteDNS/Soar-Server) on Ubuntu
24.04 amd64. After logging into the VPS with SSH, run:

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/WhiteDNS/Soar-Server/main/setup.sh)
```

The terminal prompts use English and explain each step. The repository includes
a full Persian guide. Use a separate DNS subdomain to preserve existing tunnels.
After installation, `sudo soar-manager profile` creates an IR or GLOBAL link.

## Android / اتصال گوشی

۱. لینک `soar://` تولیدشده را در Profiles > Connection > Import وارد کنید.
پروفایل اتصال و ریزالورهای همراه آن خودکار انتخاب می‌شوند.

۲. در ایران کشور IR، کشف روشن و انتقال Auto را انتخاب کنید؛ در کشورهای دیگر
GLOBAL. موتور DNS شبکهٔ فعلی و نامزدهای کشف را بررسی می‌کند؛ یک DNS عمومی
ثابت الزام اتصال نیست. نامزدها باید پاسخ سرور را احراز کنند.

۳. برای اینترنت محدود، Conservative نام DNS را تا ۱۰۱ و پاسخ را تا ۵۱۲ بایت
کوچک می‌کند و هم‌زمانی را کاهش می‌دهد. شروع اتصال به‌طور پیش‌فرض تا ۹۰ ثانیه
فرصت دارد. اسکن Soar علاوه بر احراز سرور، HTTPS با گواهی معتبر را آزمایش می‌کند.

۴. برای انتخاب کانفیگ قبلی، کارت آن یا منوی سه‌نقطه > Use profile را بزنید.
انتخاب هنگام راه‌اندازی اتصال موقتاً غیرفعال است. اتصال روشن را پس از تغییر
پروفایل قطع و دوباره برقرار کنید. جابه‌جایی Wi-Fi/دادهٔ همراه هم به اتصال مجدد
نیاز دارد تا DNS شبکه به‌روز شود.

The experimental Soar alpha app used a separate package ID; the official
release updates the regular WhiteDNS app. Existing alpha profiles can be
exported and imported. Regular WhiteDNS connection profiles remain available.

## Limits

The reference server carries TCP web traffic to public destinations on ports
80/443. UDP/QUIC, all calls/games and arbitrary ports are outside this scope.
Knowing the zone and public key allows use; there are no per-user quotas.
DNS addresses from the phone network stay local and are not put in share links.

Tests on available external networks and a phone in Thailand are evidence for
those environments. Operation inside Iran, operator differences, screen-off
and network switching still need field verification. No country-wide
availability guarantee is made.
