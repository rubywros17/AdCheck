const MAX_SELECTOR_DEPTH = 4;
const MAX_STABLE_CLASSES = 2;

export function createSelector(element: Element): string | null {
  if (element.id) {
    const idSelector = `#${CSS.escape(element.id)}`;
    if (isUnique(idSelector)) {
      return idSelector;
    }
  }

  const classSelector = createClassSelector(element);
  if (classSelector && isUnique(classSelector)) {
    return classSelector;
  }

  const path: string[] = [];
  let current: Element | null = element;
  while (current && current !== document.documentElement && path.length < MAX_SELECTOR_DEPTH) {
    path.unshift(createPathSegment(current));
    const candidate = path.join(" > ");
    if (isUnique(candidate)) {
      return candidate;
    }
    current = current.parentElement;
  }

  return path.length > 0 ? path.join(" > ") : null;
}

function createClassSelector(element: Element): string | null {
  const stableClasses = Array.from(element.classList)
    .filter(isStableClassName)
    .slice(0, MAX_STABLE_CLASSES);
  if (stableClasses.length === 0) {
    return null;
  }
  return `${element.tagName.toLowerCase()}${stableClasses.map((name) => `.${CSS.escape(name)}`).join("")}`;
}

function createPathSegment(element: Element): string {
  const tagName = element.tagName.toLowerCase();
  const parent = element.parentElement;
  if (!parent) {
    return tagName;
  }

  const sameTagSiblings = Array.from(parent.children).filter(
    (sibling) => sibling.tagName === element.tagName,
  );
  if (sameTagSiblings.length <= 1) {
    return tagName;
  }
  return `${tagName}:nth-of-type(${sameTagSiblings.indexOf(element) + 1})`;
}

function isStableClassName(className: string): boolean {
  if (!/^[a-zA-Z_][a-zA-Z0-9_-]*$/.test(className) || className.length > 40) {
    return false;
  }
  const digitCount = (className.match(/\d/g) ?? []).length;
  return digitCount <= 3;
}

function isUnique(selector: string): boolean {
  try {
    return document.querySelectorAll(selector).length === 1;
  } catch {
    return false;
  }
}
