# Privacy Policy

**DetectEverything Application**

*Last Updated: January 2025*

*Effective Date: January 2025*

---

## Introduction

AlertCat ("we," "our," or "us") is committed to protecting your privacy. This Privacy Policy explains how the DetectEverything application ("App") collects, uses, and protects your information.

**Our Core Principle: Privacy by Design**

The DetectEverything App is designed with privacy as a fundamental principle. All image processing occurs entirely on your device—we never upload, transmit, or store your photos or camera feed.

---

## 1. Information We Collect

### 1.1 Information NOT Collected

We want to be clear about what we do NOT collect:

- **Images or Photos**: All camera images are processed locally on your device and are never transmitted to our servers
- **Biometric Data**: We do not collect facial recognition or other biometric data
- **Location Data**: We do not access or collect GPS or location information
- **Personal Identifiers**: We do not collect names, email addresses, or phone numbers through the App
- **Browsing History**: We do not track your browsing activity

### 1.2 Information Processed Locally

The following data is processed entirely on your device and never leaves it:

- Camera feed for real-time object detection
- Captured images for analysis
- Detection results (object labels, confidence scores, bounding boxes)

### 1.3 Blockchain Data (User-Initiated Only)

When you choose to use the "On-Chain" feature, the following data is recorded on the public Solana blockchain:

| Data Type | Description | Storage |
|-----------|-------------|---------|
| Detection Metadata | Object class, confidence score, bounding box coordinates | Solana Blockchain (Public) |
| Wallet Address | Your Solana wallet public address | Solana Blockchain (Public) |
| Transaction Signature | Unique transaction identifier | Solana Blockchain (Public) |
| Timestamp | Block time of the transaction | Solana Blockchain (Public) |

**Important**: Blockchain transactions are:
- Publicly visible to anyone
- Permanent and cannot be deleted
- Initiated only by your explicit action (tapping "On-Chain" button)
- Signed by you through your wallet app

### 1.4 Device Permissions

The App requires the following permissions:

| Permission | Purpose | Data Handling |
|------------|---------|---------------|
| Camera | Real-time object detection | Processed locally only |
| Internet | Blockchain transactions & RPC calls | Only for Solana network communication |

---

## 2. How We Use Information

### 2.1 On-Device Processing

- Perform real-time object detection using the YOLO26 AI model
- Display detection results on your screen
- Prepare detection metadata for optional blockchain recording

### 2.2 Blockchain Recording (Optional)

When you initiate an on-chain transaction:

- Connect to Solana RPC to obtain network information
- Submit your signed transaction to the Solana network
- Save transaction records locally on your device for your reference

---

## 3. Data Storage and Security

### 3.1 Local Storage

- Detection history may be stored locally on your device
- Transaction records are saved in your device's app storage
- You can clear this data by uninstalling the App or clearing app data

### 3.2 No Cloud Storage

We do not operate servers that store your personal data. The App communicates only with:

- Solana blockchain RPC endpoints (for transactions)
- Your chosen wallet app (via Mobile Wallet Adapter protocol)

### 3.3 Security Measures

- All blockchain communications use HTTPS encryption
- Private keys are never accessed by the App (handled by your wallet)
- No analytics or tracking SDKs are included in the App

---

## 4. Third-Party Services

### 4.1 Solana Blockchain

When using the on-chain feature, your transaction is processed by the Solana network. Please review [Solana's Privacy Policy](https://solana.com/privacy-policy).

### 4.2 Mobile Wallet Adapter

The App uses the Solana Mobile Wallet Adapter protocol to communicate with wallet apps (such as Phantom, Solflare, or Seed Vault). Your wallet app's privacy policy governs how it handles your data.

### 4.3 RPC Providers

The App connects to Solana RPC endpoints to submit transactions. Default endpoint: `api.mainnet-beta.solana.com`

---

## 5. Your Rights and Choices

### 5.1 Control Over Data

You have full control over your data:

- **Camera Access**: You can revoke camera permission at any time through device settings
- **On-Chain Recording**: This feature is entirely optional and requires your explicit action
- **Local Data**: You can delete all local data by uninstalling the App

### 5.2 Blockchain Data

Due to the immutable nature of blockchain technology:

- Data recorded on the Solana blockchain cannot be modified or deleted
- Consider this before initiating any on-chain transaction

### 5.3 Opt-Out

You can use the App for object detection without ever using the blockchain features.

---

## 6. Children's Privacy

The App is not intended for children under 13 years of age. We do not knowingly collect personal information from children. If you believe a child has provided us with personal information, please contact us.

---

## 7. International Users

The App is available globally. By using the App, you acknowledge that blockchain data is stored on a decentralized network accessible worldwide.

---

## 8. Changes to This Policy

We may update this Privacy Policy from time to time. We will notify you of any changes by:

- Updating the "Last Updated" date at the top of this policy
- Providing notice through the App (for significant changes)

Your continued use of the App after any changes indicates your acceptance of the updated policy.

---

## 9. Contact Us

If you have questions about this Privacy Policy or our privacy practices, please contact us:

- **Website**: https://alertcat.info
- **Email**: privacy@alertcat.info

---

## 10. Summary

| Aspect | Our Practice |
|--------|--------------|
| Image Collection | None - all processing is local |
| Personal Data Collection | None |
| Blockchain Recording | Optional, user-initiated only |
| Data Selling | We never sell your data |
| Third-Party Tracking | None |
| Analytics | None |

---

*© 2025 AlertCat. All rights reserved.*

**Your privacy matters. DetectEverything is designed to keep your data yours.**
