/** 在文本框当前选区插入模板片段；无焦点时追加到末尾。 */
export function insertTemplateToken(
  element: HTMLTextAreaElement | HTMLInputElement | null,
  currentValue: string,
  token: string,
  onChange: (value: string) => void,
): void {
  if (!element) {
    onChange(`${currentValue}${token}`);
    return;
  }

  const start = element.selectionStart ?? currentValue.length;
  const end = element.selectionEnd ?? currentValue.length;
  const nextValue = `${currentValue.slice(0, start)}${token}${currentValue.slice(end)}`;
  const cursor = start + token.length;
  onChange(nextValue);

  requestAnimationFrame(() => {
    element.focus();
    element.setSelectionRange(cursor, cursor);
  });
}

export function formatTemplateVariable(name: string): string {
  return `{{${name}}}`;
}
