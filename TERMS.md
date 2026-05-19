# Terms of Use

**Last updated:** May 19, 2026
**Application:** SnapShop (Android, Solana Mobile)
**Repository:** https://github.com/alertcat/SnapShop

---

## 1. Acceptance of Terms

By downloading, installing, or using SnapShop (the "App"), you agree to be bound by these Terms of Use ("Terms"). If you do not agree, do not use the App. These Terms form a binding agreement between you and the App developer ("we", "us").

## 2. Description of Service

SnapShop is an Android application for Solana Mobile devices that:

- Uses on-device AI (YOLO26 via NCNN) to detect objects in real time from your device camera
- Uses cloud-based multimodal AI (Google Gemini accessed through OpenRouter) to identify the specific brand, model, and attributes of a detected object
- Links to third-party shopping platforms (Amazon, eBay, AliExpress) via an in-app WebView
- Facilitates USDC payments on the Solana blockchain through Mobile Wallet Adapter (MWA)
- Optionally records detection events on the Solana blockchain via the Memo Program

## 3. Eligibility

You must be at least 18 years old and legally capable of entering into a binding contract to use the App. You are responsible for compliance with all laws applicable to you, including cryptocurrency, e-commerce, and consumer protection regulations in your jurisdiction.

## 4. Camera and Image Data

- Camera frames captured for on-device object detection (YOLO26) **never leave your device**.
- For brand-and-model identification, the App sends a compressed, downscaled thumbnail (no larger than 384 pixels on the longer side) of the captured frame to Google Gemini via OpenRouter.
- The App does **not** store full-resolution photos, does **not** create user accounts, and does **not** transmit identifying analytics.
- You can disable the cloud identification feature at any time by not invoking the "AI Identify" action.

## 5. Third-Party Services

The App integrates with the following third-party services, each of which has its own terms and privacy policies. You are responsible for reviewing and complying with them:

| Service | Purpose | Provider |
|---|---|---|
| Amazon, eBay, AliExpress | In-app shopping | Respective platforms |
| Bitrefill | USDC-to-gift-card conversion | Bitrefill AB |
| OpenRouter + Google Gemini | Cloud AI identification | OpenRouter Inc. / Google |
| Solana blockchain | Payments and memo storage | Public network |
| Mobile Wallet Adapter | Wallet connection | Solana Mobile |

We are not affiliated with, endorsed by, or responsible for the actions, products, content, or availability of these services.

## 6. Cryptocurrency and Blockchain Transactions

- All on-chain transactions, including USDC payments and memo records, are **irreversible**.
- You are solely responsible for safekeeping your wallet credentials, seed phrase, and private keys. We have no ability to recover lost wallets.
- The App does **not** custody, hold, or have access to any user funds at any time.
- Cryptocurrency valuations are volatile. You acknowledge and accept all risks.
- The App is **not** a financial service. We provide **no** investment, tax, or legal advice.

## 7. AI Identification Accuracy

AI identification may produce incorrect, incomplete, or outdated results. Causes include:

- Limited training data for newly released products
- Model knowledge cutoff dates
- Visual ambiguity (poor lighting, occlusion, distance)
- Counterfeit or generic goods that visually resemble branded products

**Always verify product details on the destination shopping platform before completing any purchase.** We are not liable for purchases made based on incorrect AI identification.

## 8. Intellectual Property

- The App's source code is released under the MIT License (see repository).
- Product names, brand names, logos, and trademarks identified by the AI belong to their respective owners. The App does not claim ownership of third-party trademarks.
- The App does not authorize you to resell, redistribute, or sublicense identification results commercially without separate agreement.

## 9. Prohibited Use

You may not use the App to:

- Identify, purchase, or facilitate trade in goods that are illegal in your jurisdiction (including counterfeit goods, restricted weapons, controlled substances, or stolen property)
- Reverse engineer, attack, or attempt to compromise the App or its dependencies
- Automate or scrape the AI identification service in volume
- Identify people, faces, license plates, or other personal data
- Circumvent regional restrictions of third-party shopping platforms

## 10. Disclaimer of Warranties

THE APP IS PROVIDED "AS IS" AND "AS AVAILABLE" WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, OR NON-INFRINGEMENT. WE DO NOT WARRANT THAT THE APP WILL BE UNINTERRUPTED, ERROR-FREE, OR SECURE.

## 11. Limitation of Liability

TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW, IN NO EVENT SHALL WE BE LIABLE FOR ANY INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL, OR PUNITIVE DAMAGES, INCLUDING LOSS OF CRYPTOCURRENCY, DATA, GOODWILL, OR PROFITS, ARISING OUT OF OR IN CONNECTION WITH YOUR USE OF THE APP, REGARDLESS OF THE LEGAL THEORY.

OUR TOTAL AGGREGATE LIABILITY FOR ANY CLAIM ARISING OUT OF OR RELATING TO THESE TERMS OR THE APP SHALL NOT EXCEED FIFTY UNITED STATES DOLLARS (USD 50).

## 12. Changes to These Terms

We may update these Terms at any time. The "Last updated" date at the top of this page will reflect the most recent revision. Continued use of the App after revisions take effect constitutes your acceptance of the revised Terms.

## 13. Termination

We may suspend or terminate your access to the App at any time, with or without notice, for any reason, including suspected violation of these Terms.

## 14. Governing Law and Disputes

These Terms shall be governed by and construed in accordance with the laws of the jurisdiction in which the developer resides, without regard to conflict of law principles. Any dispute arising out of or in connection with these Terms shall first be attempted to be resolved through good-faith negotiation. If unresolved, the dispute shall be referred to the competent courts of that jurisdiction.

## 15. Contact

For any questions regarding these Terms, please open an issue on the project repository:
**https://github.com/alertcat/SnapShop/issues**
