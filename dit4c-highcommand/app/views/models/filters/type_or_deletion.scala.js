@(typeName: String)

function(doc, req) {
  return doc._deleted && doc.type == "@typeName";
}