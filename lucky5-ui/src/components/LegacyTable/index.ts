import { defineComponent, h, ref, type Slots } from 'vue'
import { useMediaQuery } from '@vueuse/core'
import { ElTable } from 'element-plus'
import { collectLegacyColumns, legacyField } from './columns'

const STACKED_MOBILE_HEADER_CLASS = 'legacy-mobile-table__stacked-header-column'
const STACKED_MOBILE_HEADER_WIDTH = 52

export default defineComponent({
  name: 'LegacyTable',
  inheritAttrs: false,
  props: {
    data: { type: Array<Record<string, any>>, default: () => [] },
    mobileLayout: { type: String, default: 'fluid' }
  },
  setup(props, { attrs, slots }) {
    const mobile = useMediaQuery('(max-width: 767px)')
    const toggledRows = ref(new Set<string | number>())
    return () => {
      if (!mobile.value || props.mobileLayout === 'desktop') {
        return h(ElTable, { ...attrs, data: props.data }, slots)
      }
      const definitions = collectLegacyColumns(slots.default?.() || [])
      const columns = definitions.filter((column) => column.props?.type !== 'expand')
      const expand = definitions.find((column) => column.props?.type === 'expand')
      const memberScroll = props.mobileLayout === 'member-scroll'
      const columnClassName = (definition?: Record<string, any> | null) =>
        definition?.className ?? definition?.['class-name']
      const hasColumnClass = (definition: Record<string, any> | null | undefined, name: string) =>
        String(columnClassName(definition) || '')
          .split(/\s+/)
          .includes(name)
      const rawColumnWidth = (definition?: Record<string, any> | null) => {
        if (memberScroll && hasColumnClass(definition, STACKED_MOBILE_HEADER_CLASS)) {
          return STACKED_MOBILE_HEADER_WIDTH
        }
        return definition?.minWidth ?? definition?.['min-width'] ?? definition?.width
      }
      const columnMinWidth = (definition?: Record<string, any> | null) => {
        if (!memberScroll) return undefined
        const width = rawColumnWidth(definition)
        return typeof width === 'number' || /^\d+$/.test(String(width)) ? `${width}px` : width
      }
      const memberTableMinWidth = memberScroll
        ? columns.reduce((total, column) => {
            const width = String(rawColumnWidth(column.props) ?? '').match(
              /^(\d+(?:\.\d+)?)(?:px)?$/
            )
            return total + (width ? Number(width[1]) : 0)
          }, 0)
        : 0
      const visibleColumnCount = columns.length
      const rowKey = attrs['row-key'] || attrs.rowKey
      const keyOf = (row: Record<string, any>, index: number) =>
        typeof rowKey === 'function'
          ? rowKey(row)
          : typeof rowKey === 'string'
            ? (legacyField(row, rowKey) ?? index)
            : index
      const expandedKeys = (attrs['expand-row-keys'] || attrs.expandRowKeys || []) as any[]
      const header = () => {
        const cells = columns.map((column, columnIndex) => {
          const className = columnClassName(column.props)
          const minWidth = columnMinWidth(column.props)
          const columnSlots = (column.children || {}) as Slots
          const content =
            columnSlots.header?.({ column: column.props, $index: columnIndex }) ||
            column.props?.label ||
            ''
          return h(
            'th',
            {
              scope: 'col',
              ...(className ? { class: className } : {}),
              ...(minWidth ? { style: { minWidth } } : {})
            },
            content
          )
        })
        return h('tr', cells)
      }
      const body = props.data.flatMap((row, index) => {
        const key = keyOf(row, index)
        const rowClass = attrs['row-class-name'] || attrs.rowClassName
        const context = { row, rowIndex: index }
        const expanded =
          Boolean(attrs['default-expand-all'] || expandedKeys.includes(key)) !==
          toggledRows.value.has(key)
        const cells = columns.map((column, columnIndex) => {
          const definition = column.props || {}
          const cellSlots = (column.children || {}) as Slots
          const value =
            definition.type === 'index'
              ? typeof definition.index === 'function'
                ? definition.index(index)
                : index + 1
              : legacyField(row, definition.prop)
          const cell =
            cellSlots.default?.({ row, column: definition, $index: index }) ||
            String(
              typeof definition.formatter === 'function'
                ? definition.formatter(row, definition, value, index)
                : (value ?? '')
            )
          return h(
            'td',
            {
              class: columnClassName(definition),
              style: { textAlign: definition.align || 'left', minWidth: columnMinWidth(definition) }
            },
            [
              expand && !memberScroll && columnIndex === 0
                ? h(
                    'button',
                    {
                      type: 'button',
                      class: 'legacy-mobile-table__expand',
                      'aria-label': expanded ? '收起会员操作' : '展开会员操作',
                      'aria-expanded': expanded,
                      onClick: () => {
                        const next = new Set(toggledRows.value)
                        next.has(key) ? next.delete(key) : next.add(key)
                        toggledRows.value = next
                      }
                    },
                    expanded ? '⌄' : '›'
                  )
                : null,
              h('div', { class: 'legacy-mobile-table__cell' }, cell)
            ]
          )
        })
        const rows = [
          h(
            'tr',
            {
              key,
              class: typeof rowClass === 'function' ? rowClass(context) : rowClass
            },
            cells
          )
        ]
        if (expand && !memberScroll && expanded)
          rows.push(
            h('tr', { key: `${key}-actions` }, [
              h(
                'td',
                { colspan: columns.length, class: 'legacy-mobile-table__actions' },
                ((expand.children || {}) as Slots).default?.({ row, $index: index })
              )
            ])
          )
        if (expand && memberScroll)
          rows.push(
            h('tr', { key: `${key}-actions` }, [
              h(
                'td',
                { colspan: columns.length, class: 'legacy-mobile-table__member-actions' },
                ((expand.children || {}) as Slots).default?.({ row, $index: index })
              )
            ])
          )
        return rows
      })
      if (!body.length)
        body.push(
          h('tr', [
            h(
              'td',
              {
                colspan: visibleColumnCount,
                class: 'legacy-mobile-table__empty'
              },
              String(attrs['empty-text'] || attrs.emptyText || 'No data available in table')
            )
          ])
        )
      return h(
        'div',
        {
          class: ['legacy-mobile-table', `legacy-mobile-table--${props.mobileLayout}`, attrs.class],
          style: attrs.style as any
        },
        [
          h(
            'table',
            {
              style: memberScroll
                ? { width: '100%', minWidth: `${memberTableMinWidth}px` }
                : undefined
            },
            [
              memberScroll
                ? h(
                    'colgroup',
                    columns.map((column) =>
                      h('col', {
                        class: columnClassName(column.props),
                        style: { width: columnMinWidth(column.props) }
                      })
                    )
                  )
                : null,
              h('thead', [header()]),
              h('tbody', body),
              !memberScroll &&
              (attrs['show-summary'] === '' ||
                attrs['show-summary'] === true ||
                attrs.showSummary === true)
                ? h('tfoot', [header()])
                : null
            ]
          )
        ]
      )
    }
  }
})
