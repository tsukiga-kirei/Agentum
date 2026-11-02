import assert from "node:assert/strict";
import { beforeEach, test } from "node:test";
import {
  clearModalStackForTest,
  getModalStackDepth,
  handleTopModalEscape,
  pushModalCloseHandler,
} from "../src/components/common/sysModalStack.ts";

beforeEach(() => {
  clearModalStackForTest();
});

test("空栈时触发 Esc 返回 false 并不抛出异常", () => {
  assert.equal(handleTopModalEscape(), false);
  assert.equal(getModalStackDepth(), 0);
});

test("单层弹窗按下 Esc 触发对应关闭回调并出栈", () => {
  let closed = false;
  const pop = pushModalCloseHandler(() => {
    closed = true;
  });

  assert.equal(getModalStackDepth(), 1);
  assert.equal(handleTopModalEscape(), true);
  assert.equal(closed, true);

  pop();
  assert.equal(getModalStackDepth(), 0);
});

test("嵌套弹窗按下 Esc 优先关闭顶层弹窗，底层弹窗不被误关", () => {
  const closedModals = [];
  const popParent = pushModalCloseHandler(() => {
    closedModals.push("parent");
  });
  const popChild = pushModalCloseHandler(() => {
    closedModals.push("child");
  });

  assert.equal(getModalStackDepth(), 2);

  // 第一次按 Esc：应该只关闭顶层 child
  assert.equal(handleTopModalEscape(), true);
  assert.deepEqual(closedModals, ["child"]);

  // child 卸载触发 pop
  popChild();
  assert.equal(getModalStackDepth(), 1);

  // 第二次按 Esc：应该关闭底层 parent
  assert.equal(handleTopModalEscape(), true);
  assert.deepEqual(closedModals, ["child", "parent"]);

  popParent();
  assert.equal(getModalStackDepth(), 0);
});
