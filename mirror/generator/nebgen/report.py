import collections


class Report:
    """Collects counters, per-category item lists and per-block warnings for report.json / report.md."""

    LIST_CAP = 2000

    def __init__(self):
        self.counts = collections.Counter()
        self.items = collections.defaultdict(list)
        self._item_sets = collections.defaultdict(set)
        self.warnings = collections.defaultdict(list)
        self.errors = []

    def count(self, key, n=1):
        self.counts[key] += n

    def item(self, key, value):
        if value in self._item_sets[key]:
            return
        self._item_sets[key].add(value)
        if len(self.items[key]) < self.LIST_CAP:
            self.items[key].append(value)

    def warn(self, block, message):
        self.warnings[message].append(block)

    def error(self, message):
        self.errors.append(message)

    def to_json(self):
        return {
            'counts': dict(sorted(self.counts.items())),
            'errors': self.errors,
            'warnings': {k: sorted(v) for k, v in sorted(self.warnings.items())},
            'items': {k: v for k, v in sorted(self.items.items())},
        }

    def to_markdown(self):
        lines = ['# Generator report', '']
        if self.errors:
            lines += ['## Errors', ''] + ['- ' + e for e in self.errors] + ['']
        lines += ['## Counts', '', '| Key | Value |', '|---|---|']
        lines += ['| %s | %d |' % (k, v) for k, v in sorted(self.counts.items())]
        lines.append('')
        if self.warnings:
            lines += ['## Warnings', '']
            for msg, blocks in sorted(self.warnings.items()):
                shown = ', '.join('`%s`' % b for b in sorted(blocks)[:15])
                more = ' …(+%d)' % (len(blocks) - 15) if len(blocks) > 15 else ''
                lines.append('- **%s** (%d): %s%s' % (msg, len(blocks), shown, more))
            lines.append('')
        for key, values in sorted(self.items.items()):
            lines += ['## %s (%d)' % (key, len(values)), '']
            lines += ['- `%s`' % v for v in values[:50]]
            if len(values) > 50:
                lines.append('- …')
            lines.append('')
        return '\n'.join(lines)
