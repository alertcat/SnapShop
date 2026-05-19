---
layout: default
title: SnapShop
description: AI camera shopping for Solana Mobile
---

# SnapShop

**The AI camera built for crypto shoppers.**

Point your camera at any product. SnapShop tells you the exact brand and model in seconds, then lets you buy it with USDC on Solana. Built for the Solana Seeker.

---

## Legal Documents

- [Terms of Use](terms/)
- [Privacy Policy](privacy/)

---

## Source Code

[github.com/alertcat/SnapShop](https://github.com/alertcat/SnapShop)

Open source under the MIT License.

---

## How SnapShop Works

1. **Detect** — YOLO26 NCNN runs object detection in real time, fully on device.
2. **Identify** — A 384 px thumbnail goes to Google Gemini via OpenRouter, which names the exact brand, model, and color.
3. **Shop** — Browse Amazon, eBay, and AliExpress in the in-app WebView.
4. **Pay** — USDC payment on Solana via Mobile Wallet Adapter and the Bitrefill gift-card flow.
5. **Record** — Optionally write the detection to the Solana blockchain as a Memo Program transaction.

Camera frames never leave your device for detection. Only a compressed thumbnail is sent to the cloud when you tap **AI Identify**.
