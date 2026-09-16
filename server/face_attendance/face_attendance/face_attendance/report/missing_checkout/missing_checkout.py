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
        {"label": _("Last In"), "fieldname": "last_in", "fieldtype": "Datetime", "width": 170},
    ]
    from_date = getdate(filters.get("from_date")) if filters.get("from_date") else None
    to_date = getdate(filters.get("to_date")) if filters.get("to_date") else None
    if not from_date or not to_date:
        return columns, []

    params = {"from_date": from_date, "to_date": add_days(to_date, 1)}
    conditions = ["ec.time >= %(from_date)s", "ec.time < %(to_date)s", "ec.log_type = 'IN'"]
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
               min(ec.time) as first_in,
               max(ec.time) as last_in
        from `tabEmployee Checkin` ec
        inner join `tabEmployee` e on e.name = ec.employee
        left join `tabAttendance Device` d on d.name = ec.device_id
        where {where}
          and not exists (
            select 1 from `tabEmployee Checkin` out_row
            where out_row.employee = ec.employee
              and out_row.log_type = 'OUT'
              and date(out_row.time) = date(ec.time)
          )
        group by ec.employee, e.employee_name, date(ec.time)
        order by day desc, e.employee_name asc
        """,
        params,
        as_dict=True,
    )
    return columns, data
