import { useState, type InputHTMLAttributes, type ReactNode } from "react";
import { Eye, EyeOff } from "lucide-react";

type SysPasswordInputProps = Omit<InputHTMLAttributes<HTMLInputElement>, "type"> & {
  prefixIcon?: ReactNode;
};

/** 系统密码输入框，带显示/隐藏切换，样式与 sys-field-input 一致。 */
export function SysPasswordInput({ prefixIcon, className, ...inputProps }: SysPasswordInputProps) {
  const [visible, setVisible] = useState(false);

  return (
    <div className="sys-field-input-wrap sys-field-input-wrap--password">
      {prefixIcon}
      <input
        {...inputProps}
        className={className ? `sys-field-input ${className}` : "sys-field-input"}
        type={visible ? "text" : "password"}
      />
      <button
        type="button"
        className="sys-field-password-toggle"
        onClick={() => setVisible((current) => !current)}
        aria-label={visible ? "隐藏密码" : "显示密码"}
        tabIndex={-1}
      >
        {visible ? <EyeOff size={16} aria-hidden="true" /> : <Eye size={16} aria-hidden="true" />}
      </button>
    </div>
  );
}
