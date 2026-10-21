# Lessons

## PICO API trigger scope (2026-10-21)

- Group PICO-specific operations under a PICO API Trigger selector instead of
  adding a dedicated menu for each setting.
- For this feature, expose startup registration and restoration only. Do not
  add a current-value query that the user explicitly excluded.
- Keep the parameter UI to a single input: a package name registers that app,
  and literal `null` restores the default. Do not add a checkbox for null.
