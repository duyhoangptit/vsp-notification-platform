import { Extension } from '@tiptap/core';
import { Plugin, PluginKey } from '@tiptap/pm/state';
import { Decoration, DecorationSet } from '@tiptap/pm/view';
import type { Node as ProseMirrorNode } from '@tiptap/pm/model';
import { isValidPlaceholderName, PLACEHOLDER } from './placeholders';

function decorations(doc: ProseMirrorNode): DecorationSet {
  const decos: Decoration[] = [];
  doc.descendants((node, pos) => {
    if (!node.isText || !node.text) return;
    for (const m of node.text.matchAll(PLACEHOLDER)) {
      const from = pos + (m.index ?? 0);
      const valid = isValidPlaceholderName(m[1]);
      decos.push(
        Decoration.inline(from, from + m[0].length, {
          class: valid ? 'pnh-placeholder' : 'pnh-placeholder-invalid',
          title: valid ? `Variable ${m[1]}` : 'Invalid placeholder: use ${name} with letters, digits and _',
        }),
      );
    }
  });
  return DecorationSet.create(doc, decos);
}

/** Highlights `${name}` placeholders; malformed `${…}` is underlined red (T136). */
export const PlaceholderHighlight = Extension.create({
  name: 'placeholderHighlight',
  addProseMirrorPlugins() {
    return [
      new Plugin({
        key: new PluginKey('placeholderHighlight'),
        state: {
          init: (_, { doc }) => decorations(doc),
          apply: (tr, old) => (tr.docChanged ? decorations(tr.doc) : old),
        },
        props: {
          decorations(state) {
            return this.getState(state);
          },
        },
      }),
    ];
  },
});
