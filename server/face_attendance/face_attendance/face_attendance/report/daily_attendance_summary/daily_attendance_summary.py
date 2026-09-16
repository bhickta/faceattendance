import frappe
from frappe import _
from frappe.utils import add_days, getdate


def execute(filters=None):
    filters = filters or {}
    columns = [
        {
            "label": _("Employee"),
            "fieldname": "employee",
            "fieldtype": "Link",
            "options": "Employee",
            "width": 130,
        },
        {"label": _("Employee Name"), "fieldname": "employee_name", "fieldtype": "Data", "width": 200},
        {"label": _("Date"), "fieldname": "day", "fieldtype": "Date", "width": 110},
        {"label": _("First In"), "fieldname": "first_in", "fieldtype": "Datetime", "width": 170},
        {"label": _("Last Out"), "fieldname": "last_out", "fieldtype": "Datetime", "width": 170},
        {"label": _("Punches"), "fieldname": "punches", "fieldtype": "Int", "width": 90},
        {"label": _("Gross Hours"), "fieldname": "gross_hours", "fieldtype": "Float", "width": 110},
    ]
    from_date = getdate(filters.get("from_date")) if filters.get("from_date") else None
    to_date = getdate(filters.get("to_date")) if filters.get("to_date") else None
    if not from_date or not to_date:
        return columns, []

    conditions = ["ec.time >= %(from_date)s", "ec.time < %(to_date)s"]
    params = {"from_date": from_date, "to_date": add_days(to_date, 1)}
    if filters.get("employee"):
        conditions.append("ec.employee = %(employee)s")
        params["employee"] = filters["employee"]
    if filters.get("branch"):
        conditions.append("d.branch_id = %(branch)s")
        params["branch"] = filters["branch"]
    where = " and ".join(conditions)

    data = frappe.db.sql(
        f"""
        select ec.employee,
               e.employee_name,
               date(ec.time) as day,
               min(case when ec.log_type = 'IN' then ec.time end) as first_in,
               max(case when ec.log_type = 'OUT' then ec.time end) as last_out,
               count(*) as punches
        from `tabEmployee Checkin` ec
        inner join `tabEmployee` e on e.name = ec.employee
        left join `tabAttendance Device` d on d.name = ec.device_id
        where {where}
        group by ec.employee, e.employee_name, date(ec.time)
        order by day desc, e.employee_name asc
        """,
        params,
        as_dict=True,
    )
    for row in data:
        if row["first_in"] and row["last_out"]:
            row["gross_hours"] = round(
                (row["last_out"] - row["first_in"]).total_seconds() / 3600.0, 2
            )
        else:
            row["gross_hours"] = 0.0
    return columns, data
