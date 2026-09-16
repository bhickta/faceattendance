"""Operational and integration APIs for the Face Attendance service.

These endpoints are the supported surface for customer HR systems, dashboards
and the employee portal. They are read-only except where a handler says
otherwise, always paginated, and role-checked.
"""

import frappe
from frappe import _
from frappe.utils import add_days, cint, get_system_timezone, getdate, now_datetime

DEFAULT_PAGE_SIZE = 100
MAXIMUM_PAGE_SIZE = 1000
HEALTHY_DEVICE_AGE_MINUTES = 60
MANAGED_ROLES = ["System Manager", "HR Manager"]


def _page(page, page_size):
    size = max(1, min(int(page_size or DEFAULT_PAGE_SIZE), MAXIMUM_PAGE_SIZE))
    number = max(1, int(page or 1))
    return size, (number - 1) * size


def _window(from_date, to_date):
    start = getdate(from_date) if from_date else add_days(getdate(), -30)
    end = getdate(to_date) if to_date else getdate()
    if end < start:
        frappe.throw(_("to_date must be on or after from_date"))
    return start, add_days(end, 1)


def _managed_attendance():
    authority = frappe.db.get_single_value("Face Attendance Settings", "attendance_authority")
    return authority != "External Authority"


@frappe.whitelist()
def health():
    """Compact service health snapshot for dashboards and monitoring."""
    frappe.only_for(MANAGED_ROLES)
    devices = frappe.get_all(
        "Attendance Device",
        filters={"enabled": 1},
        fields=["name", "last_sync_at"],
    )
    now = now_datetime()
    stale = [
        device.name
        for device in devices
        if not device.last_sync_at
        or (now - device.last_sync_at).total_seconds() > HEALTHY_DEVICE_AGE_MINUTES * 60
    ]
    events_last_day = frappe.db.count(
        "Face Attendance Event",
        {"creation": [">=", add_days(now, -1)]},
    )
    return {
        "status": "ok" if not stale else "degraded",
        "server_time": str(now),
        "timezone": get_system_timezone(),
        "scheduler_enabled": bool(cint(frappe.db.get_single_value("System Settings", "enable_scheduler"))),
        "devices": {
            "enabled": len(devices),
            "stale": len(stale),
            "stale_devices": stale[:25],
        },
        "templates": frappe.db.count("Biometric Template", {"enabled": 1}),
        "employees_with_biometrics": frappe.db.count(
            "Employee", {"status": "Active", "biometric_person_id": ["is", "set"]}
        ),
        "registrations_pending": frappe.db.count("Face Attendance Registration", {"status": "Pending"}),
        "deliveries_pending": frappe.db.count(
            "Attendance Delivery", {"status": ["in", ["Pending", "Retry", "Processing"]]}
        ),
        "events_last_24h": events_last_day,
        "events_awaiting_checkin": frappe.db.count(
            "Face Attendance Event",
            {"disposition": "Accepted", "employee_checkin": ["is", "not set"]},
        ),
    }


@frappe.whitelist()
def device_status():
    """Fleet view: every device with its freshness and last known sequence."""
    frappe.only_for(MANAGED_ROLES)
    now = now_datetime()
    rows = frappe.get_all(
        "Attendance Device",
        fields=[
            "name",
            "device_id",
            "enabled",
            "branch_id",
            "gate_id",
            "direction_mode",
            "assignment_version",
            "last_sync_at",
            "last_received_sequence",
            "app_version",
            "public_key",
        ],
        order_by="name asc",
    )
    for row in rows:
        age = None if not row.last_sync_at else int((now - row.last_sync_at).total_seconds() // 60)
        row["age_minutes"] = age
        row["activated"] = bool(row.pop("public_key"))
        row["healthy"] = bool(
            row.enabled and age is not None and age <= HEALTHY_DEVICE_AGE_MINUTES
        )
    return rows


@frappe.whitelist()
def roster_status():
    """How many employees are fully enrolled, and who is missing a template."""
    frappe.only_for(MANAGED_ROLES)
    employees = frappe.get_all(
        "Employee",
        filters={"status": "Active", "biometric_person_id": ["is", "set"]},
        fields=["name", "employee_name", "biometric_person_id"],
        order_by="employee_name asc",
        limit_page_length=0,
    )
    template_names = set(
        frappe.get_all(
            "Biometric Template",
            filters={"enabled": 1},
            pluck="employee",
            limit_page_length=0,
        )
    )
    enrolled = [row for row in employees if row.name in template_names]
    missing = [row for row in employees if row.name not in template_names]
    return {
        "enrolled": len(enrolled),
        "missing": len(missing),
        "missing_employees": missing[:100],
        "branches": frappe.get_all(
            "Branch",
            fields=["name"],
            order_by="name asc",
            limit_page_length=0,
        ),
    }


@frappe.whitelist()
def attendance_events(
    from_date=None,
    to_date=None,
    branch=None,
    disposition=None,
    page=1,
    page_size=DEFAULT_PAGE_SIZE,
):
    """Paginated raw events for external synchronisation."""
    frappe.only_for(MANAGED_ROLES)
    start, end = _window(from_date, to_date)
    size, offset = _page(page, page_size)
    filters = {"captured_at": ["between", [start, end]]}
    if branch:
        filters["branch_id"] = branch
    if disposition:
        filters["disposition"] = disposition
    rows = frappe.get_all(
        "Face Attendance Event",
        filters=filters,
        fields=[
            "name",
            "event_id",
            "employee",
            "branch_id",
            "gate_id",
            "device",
            "event_type",
            "captured_at",
            "captured_at_iso",
            "received_at",
            "disposition",
            "disposition_reason",
            "match_score",
            "liveness_score",
            "model_version",
            "template_version",
            "roster_version",
            "employee_checkin",
        ],
        order_by="captured_at asc",
        limit_start=offset,
        limit_page_length=size,
    )
    return {
        "from_date": str(start),
        "to_date": str(add_days(end, -1)),
        "page": int(page or 1),
        "page_size": size,
        "rows": rows,
    }


@frappe.whitelist()
def attendance_summary(
    from_date=None,
    to_date=None,
    branch=None,
    employee=None,
    page=1,
    page_size=DEFAULT_PAGE_SIZE,
):
    """Per-employee, per-day first check in, last check out and gross hours."""
    frappe.only_for(MANAGED_ROLES)
    return _attendance_summary(from_date, to_date, branch, employee, page, page_size)


def _attendance_summary(from_date, to_date, branch, employee, page, page_size):
    start, end = _window(from_date, to_date)
    size, offset = _page(page, page_size)
    managed = _managed_attendance()

    if managed:
        conditions = ["ec.time >= %(start)s", "ec.time < %(end)s"]
        params = {"start": start, "end": end, "limit": size, "offset": offset}
        if employee:
            conditions.append("ec.employee = %(employee)s")
            params["employee"] = employee
        if branch:
            conditions.append("d.branch_id = %(branch)s")
            params["branch"] = branch
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
            limit %(limit)s offset %(offset)s
            """,
            params,
            as_dict=True,
        )
        total = frappe.db.sql(
            f"""
            select count(*) from (
                select ec.employee, date(ec.time)
                from `tabEmployee Checkin` ec
                left join `tabAttendance Device` d on d.name = ec.device_id
                where {where}
                group by ec.employee, date(ec.time)
            ) grouped
            """,
            {k: v for k, v in params.items() if k not in {"limit", "offset"}},
        )[0][0]
    else:
        conditions = ["captured_at >= %(start)s", "captured_at < %(end)s", "disposition = 'Accepted'"]
        params = {"start": start, "end": end, "limit": size, "offset": offset}
        if employee:
            conditions.append("employee = %(employee)s")
            params["employee"] = employee
        if branch:
            conditions.append("branch_id = %(branch)s")
            params["branch"] = branch
        where = " and ".join(conditions)
        data = frappe.db.sql(
            f"""
            select employee,
                   date(captured_at) as day,
                   min(case when event_type = 'IN' then captured_at end) as first_in,
                   max(case when event_type = 'OUT' then captured_at end) as last_out,
                   count(*) as punches
            from `tabFace Attendance Event`
            where {where}
            group by employee, date(captured_at)
            order by day desc, employee asc
            limit %(limit)s offset %(offset)s
            """,
            params,
            as_dict=True,
        )
        total = frappe.db.sql(
            f"""
            select count(*) from (
                select employee, date(captured_at)
                from `tabFace Attendance Event`
                where {where}
                group by employee, date(captured_at)
            ) grouped
            """,
            {k: v for k, v in params.items() if k not in {"limit", "offset"}},
        )[0][0]

    for row in data:
        row["gross_hours"] = _gross_hours(row.get("first_in"), row.get("last_out"))
        if not row.get("employee_name"):
            row["employee_name"] = frappe.db.get_value("Employee", row["employee"], "employee_name")
    return {
        "source": "employee_checkin" if managed else "face_attendance_event",
        "from_date": str(start),
        "to_date": str(add_days(end, -1)),
        "page": int(page or 1),
        "page_size": size,
        "total": total,
        "rows": data,
    }


def _gross_hours(first_in, last_out):
    if not first_in or not last_out:
        return 0.0
    try:
        return round((last_out - first_in).total_seconds() / 3600.0, 2)
    except AttributeError:
        return 0.0


@frappe.whitelist()
def my_attendance(from_date=None, to_date=None):
    """Self-service attendance for the signed-in employee."""
    user = frappe.session.user
    if not user or user == "Guest":
        frappe.throw(_("Sign in required"), frappe.AuthenticationError)
    employee = frappe.db.get_value("Employee", {"user_id": user}, "name")
    if not employee:
        frappe.throw(_("No employee record is linked to your account"))
    summary = _attendance_summary(
        from_date=from_date,
        to_date=to_date,
        branch=None,
        employee=employee,
        page=1,
        page_size=MAXIMUM_PAGE_SIZE,
    )
    return {
        "employee": employee,
        "employee_name": frappe.db.get_value("Employee", employee, "employee_name"),
        **summary,
    }
