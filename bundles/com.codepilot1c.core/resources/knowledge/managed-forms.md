Managed form patterns:
- Client/server boundary: minimize server calls; batch data retrieval
- Form attributes: use DataCompositionSchema for complex data display
- Conditional appearance: use form.ConditionalAppearance for dynamic styling
- Commands: bind commands to form elements, not to code blocks
- Event handlers: ПриИзменении (OnChange) fires after value commit, not during typing
- Table interaction: use ТекущиеДанные (CurrentData) for current row access
- Dynamic list: set MainTable and CustomQuery for optimal performance
- Form opening: use ОткрытьФорму with parameters structure, avoid direct form creation
- Data locking: use FormDataToValue/ValueToFormData for object-level operations
- Notifications: use Оповестить/ОбработкаОповещения for inter-form communication
- Do not store large data in form attributes; use temporary storage (PutToTempStorage)

## Dynamic list: auto → custom query

A dynamic list's query settings live on the **form attribute**, in its
`extInfo` (`form:DynamicListExtInfo`) — not on the table item that displays it,
and not flat on the attribute. Two consequences:

- `set_item` can never change them: it addresses form **items**, whose ids are a
  different id space than form attributes.
- `type:"DynamicList"` is not a requestable type. It is the valueType the
  platform already assigned to the attribute; passing it is rejected.

Patch the attribute instead. Flat keys and the nested `extInfo` block are both
accepted (the nested block wins if both are given):

```json
{"op": "set_attribute_props", "attribute_name": "List",
 "set": {"customQuery": true, "queryText": "SELECT ... FROM Catalog.Products AS Products"}}
```

```json
{"attributes": [{"name": "List", "action": "update",
  "set": {"mainTable": "Catalog.Products", "customQuery": true, "autoFillAvailableFields": true}}]}
```

Semantics (identical to what the form editor does):

- `customQuery: true` — if you pass no `queryText` and none is stored yet, the
  query text is **generated from `mainTable`** and reported back in the
  operation summary, so check the summary for what was written. Without a
  `mainTable` the call fails: pass `queryText` explicitly or set `mainTable` first.
- `customQuery: false` — clears `queryText` and resets the derived DCS
  `fields` / `calculatedFields` / `parameters`. Passing `queryText` in the same
  call is contradictory and rejected.
- `mainTable` is never reassigned implicitly — flipping `customQuery` either way
  keeps it. Set it from a metadata FQN (`Catalog.Products`,
  `InformationRegister.Prices`); an object with no queryable database view is rejected.

Warning — the available-fields list:

- Keep `autoFillAvailableFields: true`. The platform then derives the list's
  available fields from `queryText`, and nothing else has to be authored.
- The DCS containment collections (`fields`, `calculatedFields`, `parameters`,
  `listSettings`) are **not authorable** through the plugin and are refused
  explicitly rather than ignored. If you truly need a hand-built field set,
  edit the `.form` and re-open it in the designer.
