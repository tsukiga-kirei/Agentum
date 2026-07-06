export type DeliveryDocumentKind = "word" | "excel" | "unsupported";

/** 根据交付类型与文件名判断运行态文档预览应走的渲染通道。 */
export function resolveDeliveryDocumentKind(input: {
  fileName?: string;
  deliveryType?: string;
}): DeliveryDocumentKind {
  const deliveryType = String(input.deliveryType ?? "").trim().toLowerCase();
  const fileName = String(input.fileName ?? "").trim().toLowerCase();

  if (
    deliveryType === "excel_workbook"
    || deliveryType === "excel"
    || fileName.endsWith(".xlsx")
    || fileName.endsWith(".xls")
  ) {
    return "excel";
  }

  if (
    deliveryType === "word_document"
    || deliveryType === "word"
    || fileName.endsWith(".docx")
    || fileName.endsWith(".doc")
  ) {
    return "word";
  }

  return "unsupported";
}
