/** 模板原件及试填下载复用受保护接口返回的字节，不持久化公开地址。 */
export function downloadTemplateFile(file: { blob: Blob; fileName: string }) {
  const url = URL.createObjectURL(file.blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = file.fileName;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
