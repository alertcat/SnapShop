---
layout: default
title: Privacy Policy
description: SnapShop Privacy Policy explaining data handling
permalink: /privacy/
---

# Privacy Policy

**Last updated:** May 19, 2026
**Application:** SnapShop (Android, Solana Mobile)
**Source repository:** [github.com/alertcat/SnapShop](https://github.com/alertcat/SnapShop)

---

## 1. Introduction

SnapShop is built on a **Privacy by Design** principle. The App does not maintain user accounts, does not run analytics or tracking, and does not operate any server that stores your personal data. Where cloud services are required (specifically the AI brand identification feature), the App sends only the minimum information needed and never your full-resolution photos.

This Privacy Policy explains what data the App processes, where it goes, and the controls you have over it.

## 2. Information We Collect

### 2.1 Information we do NOT collect

- No user accounts, sign-ups, emails, phone numbers, or social profiles.
- No device identifiers, advertising IDs, or fingerprints.
- No usage analytics or crash telemetry sent to us.
- No location data.
- No contacts, calendar, files, or messages.
- No wallet seed phrase, private keys, or signing material. These stay in your wallet app and are never accessible to us.

### 2.2 Information processed locally on your device

- **Camera frames**: While SnapShop is open, the camera provides a real-time video stream to the YOLO26 NCNN object detection engine. These frames are processed entirely on your device and are **never transmitted** to any server for the detection step. They are not stored, not cached, and not uploaded.
- **Detection results**: Bounding boxes and class labels produced by YOLO26 stay on your device unless you choose to record them on chain (see section 2.4).

### 2.3 Information transmitted to third-party services

When you manually trigger the **AI Identify** action, the App sends the following to the third-party cloud AI:

- A compressed JPEG thumbnail of the captured frame, downscaled so the longer side is at most **384 pixels**.
- A system prompt instructing the model to identify the visible product.

This thumbnail is transmitted to **OpenRouter**, which forwards it to **Google Gemini**. We do not retain the thumbnail; the retention policies of OpenRouter and Google apply to their own systems. See section 5 for links.

The full-resolution camera frame never leaves your device.

### 2.4 Optional blockchain data

If you tap "Record on chain", the App constructs a compact JSON memo containing detection results (class labels, bounding box coordinates, confidence values) and submits it to the Solana blockchain via the Memo Program. This action requires you to sign the transaction in your wallet. Once on chain, the data is **public and permanent**. The memo never contains your images or identifiers.

If you initiate a USDC payment, only standard Solana payment fields are written on chain (sender, recipient, amount, signature). These are visible to anyone using a block explorer.

## 3. How We Use Information

- Local camera frames: real-time object detection on your device only.
- Cloud thumbnails: one-off product identification responses, returned to your device.
- Blockchain memos: public, user-initiated recording.

We do not aggregate, analyze, sell, or share your data.

## 4. Data Storage and Security

We do not operate any server that stores user data. The App holds detection history (if any) only on your device, in private application storage that other apps cannot read. Uninstalling the App removes all local data. There is no "delete my account" flow because no account exists.

## 5. Third-Party Services

The App integrates with the following services. Each has its own privacy policy that governs how they handle data you send them. We recommend reviewing them:

- **OpenRouter** — receives thumbnails for AI identification. [openrouter.ai/privacy](https://openrouter.ai/privacy)
- **Google (Gemini)** — runs the multimodal model. [policies.google.com/privacy](https://policies.google.com/privacy)
- **Amazon, eBay, AliExpress** — when you open the in-app browser to these platforms, their own cookies, scripts, and privacy policies apply.
- **Bitrefill** — handles the USDC-to-gift-card conversion; may require its own KYC at higher thresholds. [bitrefill.com/privacy](https://www.bitrefill.com/privacy)
- **Solana network** — public blockchain. Any data you sign and submit becomes part of the immutable public ledger.
- **Mobile Wallet Adapter** — the local handoff to your wallet app; does not transmit data through us.

## 6. Your Rights and Choices

- **Disable cloud identification**: simply do not tap the "AI Identify" action. The App still works for local detection.
- **Skip blockchain recording**: do not tap "Record on chain". Detection results remain on your device only.
- **Skip USDC payments**: the shopping browser works without connecting a wallet.
- **Withdraw entirely**: uninstall the App. All local data is removed by the operating system.

## 7. Children's Privacy

SnapShop is not intended for users under 18. We do not knowingly collect data from minors. If you believe a minor is using the App, please reach out and we will help where possible. Because we collect no data, there is generally no record to delete.

## 8. International Users

SnapShop is available globally through the Solana dApp Store. By using the App, you acknowledge that any third-party services involved (AI, shopping, payments) may operate in jurisdictions different from your own. You remain responsible for compliance with your local laws regarding cryptocurrency, cross-border purchases, and import duties.

For users in the European Economic Area or other regions with strong data-protection laws (such as the GDPR or UK GDPR), our minimal-collection design means most data-subject rights (access, deletion, portability) do not produce meaningful results because we hold no personal records to begin with.

## 9. Changes to This Policy

We may update this Privacy Policy from time to time. The "Last updated" date at the top will reflect the most recent revision. Material changes may also be announced in the App's release notes. Continued use of the App after a change constitutes acceptance.

## 10. Contact Us

For any privacy-related question, please open an issue on the project repository:
[github.com/alertcat/SnapShop/issues](https://github.com/alertcat/SnapShop/issues)

## 11. Summary

| Data type | Stays on your device | Sent to a third party | Public on chain |
|---|---|---|---|
| Camera frames (full resolution) | Yes | No | No |
| Object detection results (YOLO26) | Yes | No | Only if you choose |
| Compressed thumbnail for AI identify | Source stays on device | Yes (OpenRouter + Gemini), only when you tap "AI Identify" | No |
| Memo records of detections | Only if you choose | No | Only if you choose |
| USDC payment transactions | Wallet keys stay in wallet | Only payment fields, to Solana | Yes (sender, recipient, amount) |
| Account credentials, contacts, identifiers | — | — | — |
