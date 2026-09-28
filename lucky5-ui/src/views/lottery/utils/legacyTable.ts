/** Old AdminLTE/DataTables pages repeat their headings in the table footer. */
export const legacyFooterHeaders = ({ columns }: { columns: Array<{ label?: string }> }) =>
  columns.map((column) => column.label || '')
