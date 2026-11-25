// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

const { expect, test } = require('@playwright/test')

const resourcePages = ['/vm', '/kubernetes', '/template', '/project', '/globalsetting', '/webhook']
const pagesWithPrimary = new Set(['/vm', '/kubernetes', '/template', '/project', '/webhook'])

async function measureToolbar (toolbar) {
  return toolbar.evaluate(card => {
    const rendered = element => element.offsetWidth > 4 && element.offsetHeight > 8
    const center = element => {
      const { top, height } = element.getBoundingClientRect()
      return top + height / 2
    }
    const spread = elements => {
      const positions = elements.filter(rendered).map(center)
      return positions.length > 1 ? Math.max(...positions) - Math.min(...positions) : 0
    }
    const path = card.querySelector('.breadcrumb-toolbar-path')
    const actions = card.querySelector('.breadcrumb-toolbar-actions')
    const trail = path.querySelector('.ant-breadcrumb')
    const controls = path.querySelector('.breadcrumb-controls')
    const link = trail.querySelector('a[aria-current="page"], .breadcrumb-page')
    const rightSide = [...(actions?.children || [])].flatMap(element =>
      element.matches('.row-action-button') ? [...element.querySelectorAll('.ant-btn')] : [element])
    const buttons = [...(actions?.querySelectorAll('button') || [])].filter(button =>
      rendered(button) && !button.closest('.ant-input-search, .ant-popover, .ant-dropdown'))
    const primary = buttons.filter(button => button.classList.contains('ant-btn-primary'))
    const secondary = buttons.filter(button => !primary.includes(button))
    const searchButton = actions?.querySelector('.ant-input-search-button')
    const searchBox = searchButton?.getBoundingClientRect()
    const advancedSearch = actions?.querySelector('.breadcrumb-toolbar-search .ant-input-group-addon:first-child > button')
    const advancedStyle = advancedSearch && getComputedStyle(advancedSearch)
    const expectedAdvancedSearch = !advancedSearch || (advancedSearch.querySelector('svg[data-icon="filter"]') &&
      !advancedSearch.innerText.trim() && advancedStyle.display === 'block' && advancedStyle.borderTopWidth === '0px' &&
      Math.round(advancedSearch.getBoundingClientRect().width) === 20 &&
      Math.round(advancedSearch.getBoundingClientRect().height) === 24)
    const expectedPrimary = primary.length === 0 || (primary.length === 1 && primary[0] === buttons[0] &&
      primary[0].classList.contains('ant-btn-round') && primary[0].innerText.trim() && primary[0].querySelector('svg'))
    const expectedSecondary = secondary.every(button => !button.innerText.trim() &&
      button.getAttribute('aria-label') && button.querySelector('svg'))
    const expectedSearch = !searchButton || (searchButton.querySelector('svg[data-icon="search"]') &&
      !searchButton.innerText.trim() && Math.round(searchBox.width) === 32 && Math.round(searchBox.height) === 32)
    const cardBox = card.getBoundingClientRect()
    const outside = [...card.querySelectorAll('.ant-btn, .ant-select, .ant-input-search, .ant-switch')]
      .filter(element => rendered(element) && !element.closest('.ant-popover, .ant-dropdown'))
      .some(element => {
        const bounds = element.getBoundingClientRect()
        return bounds.left < cardBox.left - 1 || bounds.right > cardBox.right + 1
      })
    return {
      leftSpread: spread([trail, ...controls.children]),
      rightSpread: spread(rightSide),
      groupSpread: actions && rendered(actions) ? Math.abs(center(path) - center(actions)) : 0,
      trailHeight: trail.getBoundingClientRect().height,
      cardFits: cardBox.left >= 0 && cardBox.right <= innerWidth,
      outside,
      currentFont: link && getComputedStyle(link).fontSize,
      currentColor: link && getComputedStyle(link).color,
      currentFits: link && link.scrollWidth <= link.clientWidth + 1,
      actionFormat: Boolean(expectedPrimary && expectedSecondary && expectedSearch && expectedAdvancedSearch)
    }
  })
}

for (const theme of ['light', 'dark']) {
  test('Mold breadcrumb keeps both groups on one desktop row in ' + theme, async ({ page, baseURL }) => {
    await page.setViewportSize({ width: 1920, height: 900 })
    await page.emulateMedia({ colorScheme: theme })
    const url = new URL(baseURL)
    url.pathname = url.pathname.replace(/\/?$/, '/')
    url.hash = '/vm'
    await page.goto(url.toString())

    const form = page.locator('#formLogin')
    await form.or(page.locator('.breadcrumb-toolbar')).first().waitFor()
    if (await form.isVisible()) {
      const { FTCTL_UI_USERNAME: username, FTCTL_UI_PASSWORD: password } = process.env
      test.skip(!username || !password, 'A Mold session or UI credentials are required')
      await form.locator('input').first().fill(username)
      await form.locator('input[type=password]').fill(password)
      await form.locator('button[type=submit]').click()
    }

    const toolbar = page.locator('.breadcrumb-toolbar').first()
    await expect(toolbar).toBeVisible()
    if (theme === 'dark') {
      await expect(page.locator('body')).toHaveClass(/dark-mode/)
    } else {
      await expect(page.locator('body')).not.toHaveClass(/dark-mode/)
    }

    for (const route of resourcePages) {
      await test.step(route, async () => {
        url.hash = route
        await page.goto(url.toString())
        await expect(toolbar.locator('.ant-breadcrumb a[aria-current="page"][href="#' + route + '"], .ant-breadcrumb .breadcrumb-page')).toBeVisible()
        if (pagesWithPrimary.has(route)) {
          await expect(toolbar.locator('.breadcrumb-toolbar-actions button.ant-btn-primary')).toBeVisible()
        }
        for (const width of [1920, 1280, 390]) {
          await page.setViewportSize({ width, height: 900 })
          await expect.poll(async () => {
            const layout = await measureToolbar(toolbar)
            return layout.cardFits && !layout.outside && layout.actionFormat && (width < 768 ||
              (layout.leftSpread <= 3 && layout.rightSpread <= 3 &&
                layout.groupSpread <= 3 && layout.trailHeight <= 25 && layout.currentFits))
          }, { message: route + ' at ' + width + 'px must fit and align' }).toBe(true)
          await expect.poll(async () => (await measureToolbar(toolbar)).currentFont).toBe('14px')
          await expect.poll(async () => (await measureToolbar(toolbar)).currentColor).toBe(
            theme === 'dark' ? 'rgb(105, 177, 255)' : 'rgb(9, 105, 218)')
        }
      })
    }
  })
}
