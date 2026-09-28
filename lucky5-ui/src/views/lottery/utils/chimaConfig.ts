/** Display a decimal zero without rounding existing quotas. */
export const quotaInputValue = (value: unknown) => {
  const amount = Number(value) || 0
  return amount === 0 ? '0.0' : String(amount)
}

/** Keep the existing numeric API payload separate from input display text. */
export const chimaConfigPayload = (form: Record<string, unknown>) =>
  Object.fromEntries(Object.entries(form).map(([key, value]) => [key, Number(value) || 0]))
