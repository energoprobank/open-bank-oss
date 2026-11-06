# openbank-everyday-service

Everyday money for the mobile app (experience v2 step 3), port 8164.

- **Biller registry** — who a customer can pay as a bill, per market. Energo-Pro's collection
  accounts are the Energo-Pro billing system's own (locally: `openbank-provider-simulator`).
- **Energo-Pro electricity** — a customer links *their own* Energo-Pro customer number (the name on
  the Energo-Pro record must be theirs, and they consent to the data sharing). The bank then shows
  that account's bills. Energo-Pro and the bank are independent companies: most bank customers are
  not Energo-Pro customers, and the bank never learns of one except from the customer.
- **Bill payments** — paid as an ordinary domestic payment the customer signs (SCA). This service
  sees `DOMESTIC_PAYMENT_CREATED`, recognises a payment to a biller with a reference as a bill
  payment, keeps its own record (the bill shows PAID from it) and tells Energo-Pro; a notice that
  did not go through is retried every two minutes. The FLUX reward (M-BILL-1) and the bill-cashback
  coupon are rewards-service's, from the same event.
- **Bill reminders** — on/off and days before the due date.
