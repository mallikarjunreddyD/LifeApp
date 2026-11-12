# Expense analytics

Open **Expenses → View Spending Analysis** and choose **Monthly** or **Yearly**.
The selected calendar period uses expense dates and the records available on the
device, including pending local writes. Recently deleted records are excluded.
Renamed and archived categories retain their historical totals by stable ID.

Both clients aggregate integer paise before formatting INR values. Slice angles are for display. The legend lists each exact amount and a share
rounded to one decimal place. The rounded shares may differ slightly from 100%.

Aggregation scans the local expense list once and sorts only categories that
have spending. The analysis screen reuses results until the selected period
or records change. PostgreSQL looks up replay pages by dataset and sequence
and uses the expense-head primary key for record lookups. Empty replay pages
leave the local projection untouched. Large-history timing on physical
devices remains part of acceptance.
