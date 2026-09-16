import frappe

no_cache = 1


def get_context(context):
    if frappe.session.user == "Guest":
        frappe.local.flags.redirect_location = "/login?redirect-to=/face-registrations"
        raise frappe.Redirect
    roles = frappe.get_roles(frappe.session.user)
    context.no_cache = 1
    context.allowed = "HR Manager" in roles or "System Manager" in roles
    return context
