# Page objects

Generate a page object class for the screen shown on the device. Use it to turn a screen into code with locators and ready-made methods.

| | |
|---|---|
| **Works on** | The current screen once the inspector has loaded a hierarchy. Output is Mobilewright (TypeScript, JavaScript) or Appium (Java, Kotlin, Python, C#). |

## Generate a page object

Pick elements and get a class with locators and methods.

**Use it**

1. Press **Generate Page Object...** (the class icon in the toolbar).
2. Tick the elements you want in the table and edit their names.
3. Pick the framework, the language and the class name.
4. Use **Copy**, **Insert at Caret** or **Save...** on the tab you are looking at.

**Good to know**

* The button is enabled only after the inspector has loaded a hierarchy.
* Only elements with a unique locator can be ticked. Controls are ticked by default, plain texts are offered but not ticked.
* Names are suggested from the test id, label or text and made unique within the class.
* Each element gets methods that fit it: `tapX()`, `fillX(text)`, `getXValue()`, `getXText()`.

## Locator style and base class

Choose where the locators live and what the class extends.

**Use it**

1. Under **Locators**, pick *Fields, screen/driver from the base class* (default), *Getters*, or *Fields, set in the constructor (no base class)*.
2. Under **Base class**, keep `BasePage` or type your own name.
3. Untick **Generate the base class** if you use your own.
4. Tick **Sort by name** to order locators and methods alphabetically.

**Good to know**

* Locators are private. Make one public only when a test needs it, for example in an `expect`.
* An own base class must expose `screen` (`this.screen`) or `driver` (`Driver` in C#).
* The base class name, language, locator style and sort setting are remembered.
* Appium locators are `By` descriptors, not `WebElement`s, which go stale.

## Which locators are used

The class uses the best unique locators from the Locators tab.

**Use it**

1. Open **Generate Page Object...**.
2. Check the locator shown for each ticked element in the code below the table.

**Good to know**

* Mobilewright uses the "in parent" variant for duplicated ids, so a repeated `add-to-cart` button works.
* Appium prefers `id`, then `accessibility id`, then UiSelector or the iOS predicate/class chain, then the XPath.
* Check the imports against your client versions, in particular the `Screen` import in the TypeScript output.

## Technical notes and what is not verified

<details>
<summary>Full reference text</summary>

### Page objects

**Generate Page Object...** (the class icon in the toolbar, enabled once the inspector has loaded a hierarchy) writes a page object class for the current screen:

* A table lists the visible elements that have a **unique** locator, with the controls (buttons, fields, switches) ticked and plain texts offered but not ticked. Elements without a unique locator are shown greyed out and cannot be ticked. Names are suggested from the test id, label or text (`email input field`, `login button`) and can be edited; they are made unique within the class.
* Pick the framework and language: **Mobilewright** (TypeScript, JavaScript) or **Appium** (Java, Kotlin, Python, C#), and the class name. The code below the table updates as you change anything.
* **Locators** says where the locators live:
  * *Fields, screen/driver from the base class* (default): all locators are private fields grouped at the top, and the page object has no constructor of its own because the base class holds the Mobilewright `screen` or the Appium `driver`. In Java, Kotlin and C# that is a one-line constructor passing the driver on (`super(driver)`).
  * *Getters*: private getters or properties instead of fields, also with the screen or driver from the base class.
  * *Fields, set in the constructor (no base class)*: a class that stands alone and builds its locators in its own constructor.
* **Base class**: the generated page extends `BasePage`. The base class is generated too (second tab). Type your own name to extend a class you already have and untick **Generate the base class**; the name is remembered, together with the language, the locator style and the sort setting. An own base class has to expose the screen as `screen` (`this.screen`) or the driver as `driver` (`Driver` in C#).
* Locators are **private**, and the generated class has one comment saying so: make a locator public only when a test needs it, for example in an `expect`.
* **Sort by name** orders the locators and their methods alphabetically.
* Each element gets its locator and the methods that fit it: `tapX()` for controls, `fillX(text)` and `getXValue()` for input fields, `getXText()` for texts. Appium locators are `By` descriptors (not `WebElement`s, which go stale).
* Locators are the best unique ones the Locators tab shows. Mobilewright uses the "in parent" variant for duplicated ids, so a repeated `add-to-cart` button works. Appium prefers `id`, then `accessibility id`, then UiSelector or the iOS predicate/class chain, then the XPath (also the one in a parent).
* **Copy**, **Insert at Caret** and **Save...** work on the tab you are looking at (Python files are saved as `login_page.py`).
* The Appium code follows the current Java, Kotlin, Python and .NET clients and the Mobilewright code its documented API (`getByTestId`, `tap`, `fill`, `getText`, `getValue`); check the imports against your versions, in particular the `Screen` import in the TypeScript output.

</details>

[Feature guide](../FEATURES.md) · [Device Tools](device-tools.md)
