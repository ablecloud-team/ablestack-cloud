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

<template>
  <div ref="rackDiagramRootRef" class="p-2 rack-diagram-root" :class="{ 'is-dark': isDarkMode }">
    <div class="toolbar-container" :class="{ 'toolbar-detail': !showRackList }">

      <div class="toolbar-view-controls">
        <div class="toolbar-left">
          <a-space :wrap="false" size="small">
            <a-input-search
              v-model:value="searchQuery"
              :placeholder="t('rackDiagram.searchPlaceholder')"
              :style="{ width: showRackList ? '320px' : '240px' }"
              allow-clear
            />
          </a-space>
          <input type="file" ref="fileInput" accept=".json" style="display: none" @change="handleImport" />
        </div>

        <div v-if="!showRackList" class="toolbar-zoom">
          <a-input-number
            :value="zoomPercentUi"
            @change="onZoomInputChange"
            @pressEnter="commitZoomUi"
            @blur="commitZoomUi"
            :min="40"
            :max="150"
            :formatter="value => `${value}%`"
            :parser="value => value.replace('%', '')"
            size="small"
            class="zoom-input"
          />

          <div class="zoom-slider-wrap">
            <a-slider
              :value="zoomPercentUi"
              @change="onZoomSliderChange"
              @afterChange="commitZoomUi"
              :min="40"
              :max="150"
              :tip-formatter="null"
            />
          </div>
        </div>
      </div>
      <div class="toolbar-actions">
        <a-space :wrap="false" size="small">
          <a-tooltip :title="!isDirty ? t('rackDiagram.msg.noChangesToSave') : ''">
            <span class="rack-save-tooltip-wrap">
              <a-button type="primary" @click="saveRackData" :loading="saving" :disabled="!isDirty">
                <SaveOutlined /> {{ t('rackDiagram.save') }}
              </a-button>
            </span>
          </a-tooltip>
          <a-dropdown
            :trigger="['click']"
            placement="bottomRight"
            overlayClassName="autogen-action-dropdown"
            class="autogen-action-dropdown__trigger rack-toolbar-action-dropdown">
            <template #overlay>
              <div class="autogen-action-dropdown__content rack-toolbar-action-dropdown__content">
                <div class="row-action-button row-action-button--dataview rack-toolbar-action-list">
                  <a-tooltip arrowPointAtCenter placement="bottomRight" :title="t('rackDiagram.addRack')">
                    <a-button class="action-button-item action-button-item--dataview" type="text" @click="openRackModal('add')">
                      <PlusOutlined class="action-button-item__icon" />
                      <span class="action-button-item__label">{{ t('rackDiagram.addRack') }}</span>
                    </a-button>
                  </a-tooltip>
                  <div class="rack-toolbar-action-divider"></div>
                  <a-tooltip arrowPointAtCenter placement="bottomRight" :title="t('rackDiagram.backup')">
                    <a-button class="action-button-item action-button-item--dataview" type="text" @click="exportToJson">
                      <FileTextOutlined class="action-button-item__icon" />
                      <span class="action-button-item__label">{{ t('rackDiagram.backup') }}</span>
                    </a-button>
                  </a-tooltip>
                  <a-tooltip arrowPointAtCenter placement="bottomRight" :title="t('rackDiagram.restore')">
                    <a-button class="action-button-item action-button-item--dataview" type="text" @click="triggerImport">
                      <UploadOutlined class="action-button-item__icon" />
                      <span class="action-button-item__label">{{ t('rackDiagram.restore') }}</span>
                    </a-button>
                  </a-tooltip>
                  <a-tooltip arrowPointAtCenter placement="bottomRight" :title="t('rackDiagram.capture')">
                    <a-button class="action-button-item action-button-item--dataview" type="text" @click="exportToImage">
                      <CameraOutlined class="action-button-item__icon" />
                      <span class="action-button-item__label">{{ t('rackDiagram.capture') }}</span>
                    </a-button>
                  </a-tooltip>
                </div>
              </div>
            </template>
            <a-button type="primary" class="autogen-action-dropdown__button">
              <template #icon><DownOutlined /></template>
              {{ t('label.actions') }}
            </a-button>
          </a-dropdown>
          <span class="toolbar-expand-divider" aria-hidden="true"></span>
          <a-tooltip :title="t(isExpanded ? 'rackDiagram.restoreSplitView' : 'rackDiagram.expandView')">
            <a-button
              type="default"
              class="toolbar-expand-btn"
              :aria-label="t(isExpanded ? 'rackDiagram.restoreSplitView' : 'rackDiagram.expandView')"
              :aria-pressed="isExpanded"
              @click="toggleExpandedView"
            >
              <template #icon>
                <CompressOutlined v-if="isExpanded" />
                <FullscreenOutlined v-else />
              </template>
            </a-button>
          </a-tooltip>
        </a-space>
      </div>

    </div>
    <a-spin :spinning="loading || saving">
      <div v-if="showRackList" class="rack-list-view">
        <div v-if="!parsedRacks.length" class="rack-list-grid rack-list-grid--empty">
          <button type="button" class="rack-list-add-card" @click="openRackModal('add')">
            <span class="rack-list-add-icon"><PlusOutlined /></span>
            <span>{{ t('rackDiagram.addRack') }}</span>
          </button>
        </div>
        <template v-else>
          <a-card class="rack-list-summary-card" :bordered="false">
            <div class="rack-list-summary-metrics">
              <div class="rack-list-summary-title">{{ t('rackDiagram.rackStatus') }}</div>
              <div class="rack-list-summary-metric">
                <span class="rack-list-summary-icon rack-list-summary-rack-icon"><RackListCardIcon /></span>
                <span class="rack-list-summary-text">
                  <span class="rack-list-summary-label">{{ t('rackDiagram.totalRackCount') }}</span>
                  <span class="rack-list-summary-value">{{ rackAggregate.totalRacks }}</span>
                </span>
              </div>
              <div class="rack-list-summary-metric">
                <span class="rack-list-summary-icon"><AppstoreOutlined /></span>
                <span class="rack-list-summary-text">
                  <span class="rack-list-summary-label">{{ t('rackDiagram.totalDeviceCount') }}</span>
                  <span class="rack-list-summary-value">{{ rackAggregate.totalDevices }}</span>
                </span>
              </div>
              <div class="rack-list-summary-metric rack-list-summary-metric-usage">
                <span class="rack-list-summary-icon"><PieChartOutlined /></span>
                <span class="rack-list-summary-text">
                  <span class="rack-list-summary-label">{{ t('rackDiagram.averageUsage') }}</span>
                  <span class="rack-list-summary-value-row">
                    <span class="rack-list-summary-value">{{ rackAggregate.averageUsage }}%</span>
                  <a-progress
                    class="rack-list-summary-progress"
                    :percent="rackAggregate.averageUsage"
                    :show-info="false"
                    size="small"
                    :stroke-width="7"
                  />
                  </span>
                </span>
              </div>
              <div class="rack-list-summary-metric">
                <span class="rack-list-summary-icon"><DatabaseOutlined /></span>
                <span class="rack-list-summary-text">
                  <span class="rack-list-summary-label">{{ t('rackDiagram.totalUsedU') }}</span>
                  <span class="rack-list-summary-value">{{ rackAggregate.totalUsedU }}U / {{ rackAggregate.totalHeight }}U</span>
                </span>
              </div>
              <div class="rack-list-summary-metric">
                <span class="rack-list-summary-icon"><InboxOutlined /></span>
                <span class="rack-list-summary-text">
                  <span class="rack-list-summary-label">{{ t('rackDiagram.totalFreeU') }}</span>
                  <span class="rack-list-summary-value">{{ rackAggregate.totalFreeU }}U</span>
                </span>
              </div>
            </div>
          </a-card>
          <div class="rack-list-view-switch">
            <a-radio-group v-model:value="rackListViewMode" size="small" button-style="solid">
              <a-radio-button value="card">
                <AppstoreOutlined /> {{ t('rackDiagram.cardView') }}
              </a-radio-button>
              <a-radio-button value="list">
                <UnorderedListOutlined /> {{ t('rackDiagram.listView') }}
              </a-radio-button>
            </a-radio-group>
          </div>
          <div v-if="rackListViewMode === 'card'" class="rack-list-grid">
          <a-card
            v-for="(rack, idx) in parsedRacks"
            :key="`rack-list-${idx}`"
            hoverable
            :class="[
              'rack-list-card',
              { 'rack-list-card--matched': hasSearchQuery && isRackMatched(rack), 'rack-list-card--dimmed': hasSearchQuery && !isRackMatched(rack) }
            ]"
            @click="openRackDetail(idx)"
          >
            <div class="rack-list-card-actions">
              <a-dropdown :trigger="['click']">
                <a-button
                  size="small"
                  type="text"
                  class="rack-list-more-btn"
                  @click.stop
                >
                  <template #icon><MoreOutlined /></template>
                </a-button>
                <template #overlay>
                  <a-menu>
                    <a-menu-item @click.stop="cloneRack(idx)">
                      <CopyOutlined /> {{ t('rackDiagram.cloneRack') }}
                    </a-menu-item>
                    <a-menu-item @click.stop="openRackModal('edit', idx)">
                      <SettingOutlined /> {{ t('rackDiagram.edit') }}
                    </a-menu-item>
                    <a-menu-divider />
                    <a-menu-item danger>
                      <a-popconfirm :title="t('rackDiagram.deleteConfirm')" :ok-text="t('label.ok')" :cancel-text="t('label.cancel')" @confirm="deleteRack(idx)">
                        <span @click.stop><DeleteOutlined /> {{ t('rackDiagram.delete') }}</span>
                      </a-popconfirm>
                    </a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
            </div>
            <div v-if="hasSearchQuery && getRackMatchCount(rack) > 0" class="rack-list-card-match">
              <a-tag color="blue">{{ t('rackDiagram.searchMatched', { count: getRackMatchCount(rack) }) }}</a-tag>
            </div>
            <div class="rack-list-card-body">
              <span class="rack-list-card-rack-icon" aria-hidden="true">
                <RackListCardIcon />
              </span>
              <div class="rack-list-card-content">
                <div class="rack-list-card-title-row">
                  <a-tooltip :title="getOverflowTitle(`list-${idx}`, rack.name)">
                  <div
                    class="rack-list-card-title"
                    :ref="setOverflowTitleRef(`list-${idx}`)"
                    @mouseenter="updateOverflowTitle(`list-${idx}`)"
                  >{{ rack.name }}</div>
                </a-tooltip>
                </div>
                <div class="rack-list-card-usage">{{ t('rackDiagram.usage') }} {{ getRackUsagePercent(rack) }}%</div>
                <div class="rack-list-card-progress-row">
                  <a-progress
                    class="rack-list-progress"
                    :percent="getRackUsagePercent(rack)"
                    :show-info="false"
                    size="small"
                    :stroke-width="8"
                  />
                  <div class="rack-list-card-usage-detail">
                    {{ getRackUsedU(rack) }}U / {{ rack.totalHeight }}U
                  </div>
                </div>
                <div class="rack-list-card-stats">
                  <div class="rack-list-card-stat">
                    <span class="rack-list-card-stat-label">{{ t('rackDiagram.freeSpace') }}</span>
                    <strong>{{ getRackFreeU(rack) }}U</strong>
                  </div>
                  <div class="rack-list-card-stat">
                    <span class="rack-list-card-stat-label">{{ t('rackDiagram.deviceCount') }}</span>
                    <strong>{{ getRackDeviceCount(rack) }}{{ t('rackDiagram.countSuffix') }}</strong>
                  </div>
                </div>
                <div class="rack-list-card-footer">
                  <span><CalendarOutlined /> {{ t('label.created') }} {{ getRackCreatedDate(rack) }}</span>
                  <a-tooltip :title="`${t('rackDiagram.rackLocation')} ${getRackLocation(rack)}`">
                    <span class="rack-list-card-location"><EnvironmentOutlined /> {{ t('rackDiagram.rackLocation') }} {{ getRackLocation(rack) }}</span>
                  </a-tooltip>
                </div>
              </div>
            </div>
          </a-card>
          <button type="button" class="rack-list-add-card" @click="openRackModal('add')">
            <span class="rack-list-add-icon"><PlusOutlined /></span>
            <span>{{ t('rackDiagram.addRack') }}</span>
          </button>
        </div>
        <div v-else class="rack-list-table">
          <div
            v-for="(rack, idx) in parsedRacks"
            :key="`rack-list-row-${idx}`"
            :class="[
              'rack-list-row',
              { 'rack-list-row--matched': hasSearchQuery && isRackMatched(rack), 'rack-list-row--dimmed': hasSearchQuery && !isRackMatched(rack) }
            ]"
            @click="openRackDetail(idx)"
          >
            <div class="rack-list-row-main">
              <span class="rack-list-row-icon" aria-hidden="true"><RackListCardIcon /></span>
              <div class="rack-list-row-title-wrap">
                <div class="rack-list-row-title-line">
                  <a-tooltip :title="getOverflowTitle(`list-row-${idx}`, rack.name)">
                    <span
                      class="rack-list-row-title"
                      :ref="setOverflowTitleRef(`list-row-${idx}`)"
                      @mouseenter="updateOverflowTitle(`list-row-${idx}`)"
                    >{{ rack.name }}</span>
                  </a-tooltip>
                  <a-tag v-if="hasSearchQuery && getRackMatchCount(rack) > 0" color="blue" class="rack-list-row-match">
                    {{ t('rackDiagram.searchMatched', { count: getRackMatchCount(rack) }) }}
                  </a-tag>
                </div>
                <div class="rack-list-row-meta">
                  <span><CalendarOutlined /> {{ t('label.created') }} {{ getRackCreatedDate(rack) }}</span>
                  <a-tooltip :title="`${t('rackDiagram.rackLocation')} ${getRackLocation(rack)}`">
                    <span class="rack-list-row-location"><EnvironmentOutlined /> {{ t('rackDiagram.rackLocation') }} {{ getRackLocation(rack) }}</span>
                  </a-tooltip>
                </div>
              </div>
            </div>
            <div class="rack-list-row-usage">
              <span class="rack-list-row-label">{{ t('rackDiagram.usage') }}</span>
              <strong>{{ getRackUsagePercent(rack) }}%</strong>
              <a-progress
                class="rack-list-row-progress"
                :percent="getRackUsagePercent(rack)"
                :show-info="false"
                size="small"
                :stroke-width="7"
              />
              <span class="rack-list-row-used">{{ getRackUsedU(rack) }}U / {{ rack.totalHeight }}U</span>
            </div>
            <div class="rack-list-row-stats">
              <span>{{ t('rackDiagram.freeSpace') }} <strong>{{ getRackFreeU(rack) }}U</strong></span>
              <span>{{ t('rackDiagram.deviceCount') }} <strong>{{ getRackDeviceCount(rack) }}{{ t('rackDiagram.countSuffix') }}</strong></span>
            </div>
            <div class="rack-list-row-actions" @click.stop>
              <a-dropdown :trigger="['click']">
                <a-button size="small" type="text" class="rack-list-more-btn">
                  <template #icon><MoreOutlined /></template>
                </a-button>
                <template #overlay>
                  <a-menu>
                    <a-menu-item @click.stop="cloneRack(idx)">
                      <CopyOutlined /> {{ t('rackDiagram.cloneRack') }}
                    </a-menu-item>
                    <a-menu-item @click.stop="openRackModal('edit', idx)">
                      <SettingOutlined /> {{ t('rackDiagram.edit') }}
                    </a-menu-item>
                    <a-menu-divider />
                    <a-menu-item danger>
                      <a-popconfirm :title="t('rackDiagram.deleteConfirm')" :ok-text="t('label.ok')" :cancel-text="t('label.cancel')" @confirm="deleteRack(idx)">
                        <span @click.stop><DeleteOutlined /> {{ t('rackDiagram.delete') }}</span>
                      </a-popconfirm>
                    </a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
            </div>
          </div>
          <button type="button" class="rack-list-add-row" @click="openRackModal('add')">
            <PlusOutlined />
            <span>{{ t('rackDiagram.addRack') }}</span>
          </button>
        </div>
        </template>
      </div>
      <div v-else class="rack-canvas">
        <div
          class="rack-detail-layout"
          ref="rackDetailLayoutRef"
        >
        <div class="rack-main-pane" ref="rackMainPaneRef">
        <div class="rack-zoom-wrapper" :style="zoomWrapperStyle">

          <a-empty v-if="!parsedRacks.length" :description="t('rackDiagram.emptyRack')" style="margin-top: 50px;" />

          <div class="rack-container" v-else>
            <div class="rack-wrapper" v-for="{ rack, index: rIndex } in visibleRackEntries" :key="`rack-${rIndex}`">

              <div class="rack-header">
                <div class="rack-header-top">
                  <div class="rack-header-title-group">
                    <a-tooltip
                      placement="top"
                      :title="getOverflowTitle(`detail-${rIndex}`, rack.name)"
                    >
                      <span
                        class="rack-header-title"
                        :ref="setOverflowTitleRef(`detail-${rIndex}`)"
                        @mouseenter="updateOverflowTitle(`detail-${rIndex}`)"
                      >
                        {{ rack.name }}
                      </span>
                    </a-tooltip>
                  </div>

                  <div class="rack-header-actions">
                    <div class="rack-header-action-group rack-header-action-group-list">
                      <a-tooltip :title="t('rackDiagram.backToList')">
                        <a-button class="rack-header-action-btn" @click="backToRackList">
                          <template #icon><UnorderedListOutlined /></template>
                        </a-button>
                      </a-tooltip>
                    </div>
                    <span class="rack-header-action-separator"></span>
                    <div class="rack-header-action-group">
                      <a-tooltip :title="t('rackDiagram.edit')">
                        <a-button class="rack-header-action-btn" @click="openRackModal('edit', rIndex)">
                          <template #icon><EditOutlined /></template>
                        </a-button>
                      </a-tooltip>
                      <a-tooltip :title="t('rackDiagram.cloneRack')">
                        <a-button class="rack-header-action-btn" @click="cloneRack(rIndex)">
                          <template #icon><CopyOutlined /></template>
                        </a-button>
                      </a-tooltip>
                      <a-popconfirm :title="t('rackDiagram.deleteConfirm')" :ok-text="t('label.ok')" :cancel-text="t('label.cancel')" @confirm="deleteRack(rIndex)">
                        <a-tooltip :title="t('rackDiagram.delete')">
                          <a-button class="rack-header-action-btn" danger>
                            <template #icon><DeleteOutlined /></template>
                          </a-button>
                        </a-tooltip>
                      </a-popconfirm>
                    </div>
                  </div>
                </div>
                <div class="rack-header-meta-row">
                  <span class="rack-header-meta-item">
                    <span>{{ getRackUsedU(rack) }}U / {{ rack.totalHeight }}U</span>
                  </span>
                  <span class="rack-header-meta-divider">·</span>
                  <span class="rack-header-meta-item">
                    <CalendarOutlined />
                    <span>{{ t('label.created') }} {{ getRackCreatedDate(rack) }}</span>
                  </span>
                  <span class="rack-header-meta-divider">·</span>
                  <span class="rack-header-meta-item rack-header-meta-location">
                    <EnvironmentOutlined />
                    <a-tooltip :title="`${t('rackDiagram.rackLocation')} ${getRackLocation(rack)}`">
                      <span class="rack-header-meta-ellipsis">
                        {{ t('rackDiagram.rackLocation') }} {{ getRackLocation(rack) }}
                      </span>
                    </a-tooltip>
                  </span>
                </div>
              </div>

              <div class="rack-body">

                <div class="rack-ruler">
                  <div
                    v-for="u in rack.totalHeight"
                    :key="u"
                    class="ruler-number"
                    :class="{ 'ruler-number-selected': isSelectedRulerUnit(rack, rIndex, u) }"
                  >
                    {{ rack.totalHeight - u + 1 }}
                  </div>
                </div>

                <div class="rack-frame" @dragover.prevent @drop.stop="onDropRackFrame(rIndex, $event)">
                  <div
                    v-for="(item, iIndex) in rack.items"
                    :key="iIndex"
                    class="rack-item"
                    :class="{ 'rack-item-selected': isSelectedItem(rIndex, iIndex), 'rack-item-hoverable': item.type !== 'gap' }"
                    @dragover.prevent
                    :data-item-type="item.type"
                    :style="{
                      height: (item.height * RACK_UNIT_HEIGHT) + 'px',
                      opacity: isMatched(item) ? 1 : 0.2,
                      filter: isMatched(item) ? 'none' : 'grayscale(100%)'
                    }"
                  >
                    <div v-if="item.type === 'gap'" class="gap-content" @click="openDeviceModal(rIndex, iIndex)">
                      <span>+ {{ item.height }}U {{ t('rackDiagram.gapAddDevice') }}</span>
                    </div>

                    <div
                      v-else
                      class="device-content"
                      :class="[
                        `device-${item.type}`,
                        {
                          'device-content-selected': isSelectedItem(rIndex, iIndex),
                          'device-content-compact': Number(item.height) === 1
                        }
                      ]"
                      draggable="true"
                      @dragstart="onDragStart(rIndex, iIndex)"
                      @click="selectDevice(rIndex, iIndex)"
                    >

                      <div class="device-top-line" :style="{ backgroundColor: item.type === 'blank' ? 'transparent' : getIconColor(getDevicePanelType(item)) }"></div>

                      <div
                        class="device-pattern"
                        :class="`panel-${getDevicePanelType(item)}`"
                        v-html="renderDevicePanel(item)"
                      ></div>

                      <svg v-if="item.type === 'ups'" class="ups-watermark" viewBox="0 0 24 24" preserveAspectRatio="xMidYMid meet">
                        <path d="M13 2.05v9.45h4.5l-8.5 10.45v-9.45H4.5L13 2.05z" fill="rgba(255,255,255,0.03)" />
                      </svg>

                      <div
                        class="device-icon-overlay"
                        :style="{ color: getIconColor(getDevicePanelType(item)) }"
                        v-html="renderBadgeIcon(getDevicePanelType(item))"
                      ></div>

                      <div class="device-name-tag">
                        <div class="tag-content">
                          <span v-if="item.type !== 'custom'" class="tag-text">{{ item.label }}</span>
                          <span v-else class="tag-text">{{ item.customType }}</span>
                          <span class="tag-badge">{{ item.height }}U</span>
                        </div>
                      </div>

                      <div class="device-actions">
                        <a-tooltip :title="t('rackDiagram.cloneDevice')" :mouseEnterDelay="0.1">
                          <a-button size="small" type="text" @click.stop="cloneItem(rIndex, iIndex)">
                            <CopyOutlined />
                          </a-button>
                        </a-tooltip>

                        <a-tooltip :title="t('rackDiagram.moveToOtherRack')" :mouseEnterDelay="0.1">
                          <a-button size="small" type="text" @click.stop="openMoveDeviceModal(rIndex, iIndex)">
                            <ExportOutlined />
                          </a-button>
                        </a-tooltip>

                        <a-tooltip :title="t('rackDiagram.deviceConfig')" :mouseEnterDelay="0.1">
                          <a-button size="small" type="text" @click.stop="openDeviceModal(rIndex, iIndex)">
                            <SettingOutlined />
                          </a-button>
                        </a-tooltip>

                        <a-dropdown v-if="hasActionMenu(item)" :trigger="['click']">
                          <a-tooltip :title="t('rackDiagram.deviceActions')" :mouseEnterDelay="0.1">
                            <a-button size="small" type="text" class="device-more-btn" @click.stop>
                              <MoreOutlined />
                            </a-button>
                          </a-tooltip>
                          <template #overlay>
                            <a-menu @click="({ key }) => handleHostActionMenu(key, item)">
                              <a-menu-item v-if="isHostLinked(item)" key="host-detail"><LinkOutlined /> {{ t('rackDiagram.hostDetail') }}</a-menu-item>
                              <a-menu-item v-if="isHostLinked(item)" key="host-vms"><UnorderedListOutlined /> {{ t('rackDiagram.hostVmList') }}</a-menu-item>
                              <a-menu-item v-if="isHostLinked(item)" key="host-oobm"><LaptopOutlined /> {{ t('rackDiagram.hostOobmPortal') }}</a-menu-item>
                              <a-menu-item v-if="isHostLinked(item)" key="host-cube"><AppstoreOutlined /> {{ t('rackDiagram.hostCubePortal') }}</a-menu-item>
                              <a-menu-divider v-if="isHostLinked(item) && hasQuickLinks(item)" />
                              <a-menu-item v-for="(link, lIdx) in getQuickLinks(item)" :key="`quick-${lIdx}`">
                                <LinkOutlined /> {{ link.label || link.url }}
                              </a-menu-item>
                            </a-menu>
                          </template>
                        </a-dropdown>

                        <a-popconfirm :title="t('rackDiagram.deleteConfirm')" :ok-text="t('label.ok')" :cancel-text="t('label.cancel')" @confirm="deleteItem(rIndex, iIndex)" placement="topRight">
                          <a-tooltip :title="t('rackDiagram.delete')" :mouseEnterDelay="0.1">
                            <a-button size="small" type="text" danger @click.stop>
                              <DeleteOutlined />
                            </a-button>
                          </a-tooltip>
                        </a-popconfirm>

                      </div>

                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
        </div>
        <transition
          name="rack-inspector"
          @before-enter="handleSidePaneBeforeEnter"
          @after-enter="handleSidePaneAfterEnter"
          @after-leave="handleSidePaneAfterLeave"
        >
        <div
          v-if="selectedDevice"
          ref="rackSidePaneSlotRef"
          class="rack-side-pane-slot"
          :style="sidePaneSlotStyle"
        >
          <div
            class="rack-side-pane"
          >
          <a-card size="small" class="rack-side-pane-card">
            <template #title>{{ t('rackDiagram.deviceInfo') }}</template>
            <template #extra>
              <a-button size="small" type="text" class="device-info-close-btn" @click="clearSelectedDevice">
                <CloseOutlined />
              </a-button>
            </template>
            <div class="device-summary-header">
              <div
                class="device-summary-icon"
                :style="{ color: getIconColor(getDevicePanelType(sidePanelDevice)) }"
                v-html="renderBadgeIcon(getDevicePanelType(sidePanelDevice))"
              ></div>
              <div class="device-summary-main">
                <div class="device-summary-title-row">
                  <span class="device-summary-title">{{ selectedDeviceDraft.label || '-' }}</span>
                  <span class="device-summary-badge">{{ selectedDeviceDraft.height }}U</span>
                </div>
                <div class="device-summary-subtitle">
                  <span>{{ selectedDeviceTypeLabel }}</span>
                  <span v-if="selectedDeviceIp">· {{ t('label.ip') }} {{ selectedDeviceIp }}</span>
                  <span v-if="selectedDeviceStatusLabel">·</span>
                  <span v-if="selectedDeviceStatusLabel" class="device-status-inline">
                    <span class="device-status-dot"></span>{{ selectedDeviceStatusLabel }}
                  </span>
                </div>
              </div>
            </div>

            <a-tabs v-model:activeKey="deviceInfoActiveTab" size="small" class="device-info-tabs">
              <a-tab-pane key="summary" :tab="t('rackDiagram.tabSummary')">
                <div class="device-info-section">
                  <div class="device-info-section-heading">
                    <div class="device-info-section-title">{{ t('rackDiagram.sectionBasicInfo') }}</div>
                    <a-button size="small" type="text" class="device-section-edit-btn" @click="activateInlineField('basic')">
                      <EditOutlined />
                    </a-button>
                  </div>
                  <div class="device-inline-form">
                    <div class="device-inline-row">
                      <label>{{ t('rackDiagram.deviceName') }}</label>
                      <div class="device-readonly-value">
                        <span v-if="activeInlineField !== 'basic'">{{ selectedDeviceDraft.label || '-' }}</span>
                        <a-input
                          v-else
                          v-model:value="selectedDeviceDraft.label"
                          size="small"
                          :maxlength="60"
                          @pressEnter="finishInlineEdit"
                        />
                      </div>
                    </div>
                    <div class="device-inline-row">
                      <label>{{ t('rackDiagram.height') }}</label>
                      <div class="device-readonly-value">
                        <span v-if="activeInlineField !== 'basic'">{{ selectedDeviceDraft.height }}U</span>
                        <a-input-number
                          v-else
                          v-model:value="selectedDeviceDraft.height"
                          :min="1"
                          :max="sidePanelMaxAllowedHeight"
                          :precision="0"
                          size="small"
                          style="width: 100%"
                          @pressEnter="finishInlineEdit"
                        />
                      </div>
                    </div>
                    <div class="device-inline-row">
                      <label>{{ t('rackDiagram.position') }}</label>
                      <div class="device-readonly-value">
                        <span v-if="activeInlineField !== 'basic'">{{ draftPositionLabel }}</span>
                        <a-input-number
                          v-else
                          v-model:value="selectedDeviceDraft.startU"
                          :min="1"
                          :max="selectedRackTotalHeight"
                          :precision="0"
                          size="small"
                          style="width: 100%"
                          @pressEnter="finishInlineEdit"
                        />
                      </div>
                    </div>
                  </div>
                </div>
                <div class="device-info-section">
                  <div class="device-info-section-heading">
                    <div class="device-info-section-title">{{ t('rackDiagram.hardwareInfo') }}</div>
                    <a-button size="small" type="text" class="device-section-edit-btn" @click="activateInlineField('hardware')">
                      <EditOutlined />
                    </a-button>
                  </div>
                  <div class="device-inline-form">
                    <div v-for="field in hardwareInfoFields" :key="field.key" class="device-inline-row">
                      <label>{{ t(field.labelKey) }}</label>
                      <div class="device-readonly-value">
                        <span v-if="activeInlineField !== 'hardware'">{{ selectedDeviceDraft.hardwareInfo[field.key] || '-' }}</span>
                        <a-auto-complete
                          v-else-if="field.key === 'vendor'"
                          v-model:value="selectedDeviceDraft.hardwareInfo.vendor"
                          :options="hardwareVendorOptions"
                          :filter-option="filterHardwareVendorOption"
                          :placeholder="t('rackDiagram.vendorPlaceholder')"
                          size="small"
                          style="width: 100%"
                          @select="markInlineDraftChanged"
                          @change="markInlineDraftChanged"
                          @pressEnter="finishInlineEdit"
                        />
                        <a-date-picker
                          v-else-if="field.type === 'date'"
                          v-model:value="selectedDeviceDraft.hardwareInfo[field.key]"
                          value-format="YYYY-MM-DD"
                          size="small"
                          style="width: 100%"
                          @change="markInlineDraftChanged"
                        />
                        <a-input
                          v-else
                          v-model:value="selectedDeviceDraft.hardwareInfo[field.key]"
                          :maxlength="128"
                          size="small"
                          @pressEnter="finishInlineEdit"
                        />
                      </div>
                    </div>
                  </div>
                </div>
                <div class="device-info-section">
                  <div class="device-info-section-heading">
                    <div class="device-info-section-title">{{ t('rackDiagram.deviceSpecs') }}</div>
                    <a-button size="small" type="text" class="device-section-edit-btn" @click="activateInlineField('specs')">
                      <EditOutlined />
                    </a-button>
                  </div>
                  <div class="device-memo-edit-block" @click.stop @mousedown.stop>
                    <div
                      class="device-memo-table"
                      :class="{ 'device-memo-table-read': activeInlineField !== 'specs', 'device-memo-table-edit': activeInlineField === 'specs' }"
                    >
                      <div class="device-memo-table-head">
                        <div>{{ t('rackDiagram.memoKey') }}</div>
                        <div>{{ t('rackDiagram.memoValue') }}</div>
                        <div v-if="activeInlineField === 'specs'">{{ t('rackDiagram.quickLinkAction') }}</div>
                      </div>
                      <div v-for="(row, idx) in visibleDeviceSpecRows" :key="row.id" class="device-memo-table-row">
                        <template v-if="activeInlineField === 'specs'">
                          <a-select
                            v-model:value="row.key"
                            :options="deviceSpecKeyOptions"
                            :show-search="true"
                            option-filter-prop="value"
                            :filter-option="filterDeviceSpecKeyOption"
                            size="small"
                            :placeholder="t('rackDiagram.deviceSpecKeyPlaceholder')"
                            style="width: 100%"
                            @click.stop
                            @mousedown.stop
                            @change="markInlineDraftChanged"
                            @keydown.enter.stop="finishInlineEdit"
                          />
                          <span v-if="isNumericDeviceSpecRow(row)" class="device-spec-number-wrap" @click.stop @mousedown.stop>
                            <a-input-number
                              :value="getSpecNumericEditValue(row)"
                              size="small"
                              :min="0"
                              :precision="getSpecNumericPrecision(row)"
                              :placeholder="t('rackDiagram.deviceSpecValuePlaceholder')"
                              @change="value => setSpecNumericEditValue(row, value)"
                              @pressEnter="finishInlineEdit"
                            />
                            <span v-if="getDeviceSpecUnit(row)" class="device-spec-unit">{{ getDeviceSpecUnit(row) }}</span>
                          </span>
                          <a-input
                            v-else
                            v-model:value="row.value"
                            size="small"
                            :placeholder="t('rackDiagram.deviceSpecValuePlaceholder')"
                            @click.stop
                            @change="markInlineDraftChanged"
                            @pressEnter="finishInlineEdit"
                          />
                          <a-button size="small" type="text" danger @click.stop="removeInlineSpecRow(idx)">
                            <DeleteOutlined />
                          </a-button>
                        </template>
                        <template v-else>
                          <span class="device-memo-cell-text">{{ row.key || '-' }}</span>
                          <span class="device-memo-cell-text">{{ row.value || '-' }}</span>
                        </template>
                      </div>
                      <div v-if="!visibleDeviceSpecRows.length" class="device-memo-empty">
                        <a-empty :description="t('label.no.data')" />
                      </div>
                    </div>
                    <a-button v-if="activeInlineField === 'specs'" size="small" class="device-memo-add-btn" @click.stop="addInlineSpecRow">
                      <PlusOutlined /> {{ t('rackDiagram.addSpecRow') }}
                    </a-button>
                  </div>
                </div>
                <div class="device-info-section">
                  <div class="device-info-section-heading">
                    <div class="device-info-section-title">{{ t('rackDiagram.deviceMemo') }}</div>
                    <a-button size="small" type="text" class="device-section-edit-btn" @click="activateInlineField('memo')">
                      <EditOutlined />
                    </a-button>
                  </div>
                  <div
                    v-if="activeInlineField !== 'memo'"
                    class="device-free-memo-read"
                  >
                    {{ selectedDeviceDraft.memo || '-' }}
                  </div>
                  <a-textarea
                    v-else
                    v-model:value="selectedDeviceDraft.memo"
                    :auto-size="{ minRows: 3, maxRows: 8 }"
                    :placeholder="t('rackDiagram.deviceMemoPlaceholder')"
                  />
                </div>
              </a-tab-pane>
              <a-tab-pane key="connections" :tab="t('rackDiagram.tabConnections')">
                <div v-if="selectedDevice.type === 'server'" class="device-info-section">
                  <div class="device-info-section-heading">
                    <div class="device-info-section-title">{{ t('rackDiagram.infraAsset') }}</div>
                  </div>
                  <div class="device-info-table device-host-compact-table">
                    <div class="device-info-table-row">
                      <div>{{ t('rackDiagram.hostLabel') }}</div>
                      <div class="device-info-copy-value">
                        <a-tooltip :title="selectedDeviceLinkedAssetName">
                          <span>{{ selectedDeviceLinkedAssetName || '-' }}</span>
                        </a-tooltip>
                        <a-tooltip :title="t('label.copy.clipboard')">
                          <a-button size="small" type="text" class="device-linked-asset-copy" @click="copyTextToClipboard(selectedDeviceLinkedAssetName)">
                            <CopyOutlined />
                          </a-button>
                        </a-tooltip>
                      </div>
                    </div>
                    <div class="device-info-table-row">
                      <div>{{ t('label.ip') }}</div>
                      <div class="device-info-copy-value">
                        <span>{{ selectedDeviceLinkedAssetIp || '-' }}</span>
                        <a-tooltip :title="t('label.copy.clipboard')">
                          <a-button size="small" type="text" class="device-linked-asset-copy" @click="copyTextToClipboard(selectedDeviceLinkedAssetIp)">
                            <CopyOutlined />
                          </a-button>
                        </a-tooltip>
                      </div>
                    </div>
                    <div v-for="(row, idx) in selectedDeviceHostInfoCompact" :key="`host-compact-${idx}`" class="device-info-table-row">
                      <div>{{ row.label }}</div>
                      <div>{{ row.value }}</div>
                    </div>
                  </div>
                  <div class="device-infra-asset-actions">
                    <div class="device-infra-asset-action-help">{{ t('rackDiagram.changeInfraAssetInlineHelp') }}</div>
                    <a-button size="small" class="device-link-asset-change-btn" @click="openAssetLinkModal">
                      <LinkOutlined /> {{ t('rackDiagram.changeInfraAsset') }}
                    </a-button>
                  </div>
                </div>
                <div class="device-info-section">
                  <div class="device-info-section-title">{{ t('rackDiagram.links') }}</div>
                  <div class="device-link-list">
                    <template v-if="isHostLinked(sidePanelDevice)">
                      <div class="device-link-group-title">{{ t('rackDiagram.hostLinks') }}</div>
                      <a-button size="small" class="device-link-button" @click="goToLinkedHost(sidePanelDevice)">
                        <span class="device-link-button-main"><LinkOutlined /> <span>{{ t('rackDiagram.hostDetail') }}</span></span>
                        <ExportOutlined class="device-link-button-arrow" />
                      </a-button>
                      <a-button size="small" class="device-link-button" @click="openLinkedHostVmModal(sidePanelDevice)">
                        <span class="device-link-button-main"><UnorderedListOutlined /> <span>{{ t('rackDiagram.hostVmList') }}</span></span>
                        <ExportOutlined class="device-link-button-arrow" />
                      </a-button>
                      <a-button size="small" class="device-link-button" @click="openLinkedHostOobm(sidePanelDevice)">
                        <span class="device-link-button-main"><LaptopOutlined /> <span>{{ t('rackDiagram.hostOobmPortal') }}</span></span>
                        <ExportOutlined class="device-link-button-arrow" />
                      </a-button>
                      <a-button size="small" class="device-link-button" @click="openLinkedHostCube(sidePanelDevice)">
                        <span class="device-link-button-main"><AppstoreOutlined /> <span>{{ t('rackDiagram.hostCubePortal') }}</span></span>
                        <ExportOutlined class="device-link-button-arrow" />
                      </a-button>
                    </template>
                    <div v-if="selectedDeviceCustomLinks.length" class="device-link-custom-block">
                      <div class="device-link-group-title" :class="{ 'device-link-group-title-spaced': isHostLinked(sidePanelDevice) }">
                        {{ t('rackDiagram.customLinks') }}
                      </div>
                      <div class="device-link-list-read">
                        <a-button
                          v-for="(link, idx) in selectedDeviceCustomLinks"
                          :key="`side-link-${idx}`"
                          size="small"
                          class="device-link-button"
                          @click.stop="openQuickLink(link)"
                        >
                          <span class="device-link-button-main"><LinkOutlined /> <span>{{ link.label || link.url }}</span></span>
                          <ExportOutlined class="device-link-button-arrow" />
                        </a-button>
                      </div>
                    </div>
                    <a-empty
                      v-else-if="!isHostLinked(sidePanelDevice)"
                      :description="t('rackDiagram.noQuickLinks')"
                    />
                  </div>
                </div>
              </a-tab-pane>
            </a-tabs>

            <div class="device-info-actions">
              <a-button size="small" @click="openSelectedDeviceModal">
                <SettingOutlined /> {{ t('rackDiagram.deviceConfig') }}
              </a-button>
              <a-popconfirm :title="t('rackDiagram.deleteConfirm')" :ok-text="t('label.ok')" :cancel-text="t('label.cancel')" @confirm="deleteItem(selectedDeviceRackIndex, selectedDeviceItemIndex)">
                <a-button size="small" danger>
                  <DeleteOutlined /> {{ t('rackDiagram.deviceDelete') }}
                </a-button>
              </a-popconfirm>
            </div>

          </a-card>
          </div>
        </div>
        </transition>
        </div>
      </div>
    </a-spin>

    <a-modal
      v-model:visible="rackModalVisible"
      :title="rackModalMode === 'add' ? t('rackDiagram.addRack') : t('label.edit')"
      :ok-text="t('label.ok')"
      :cancel-text="t('label.cancel')"
      @ok="submitRackModal"
      @cancel="closeRackModal"
      destroyOnClose
    >
      <a-form layout="vertical">
        <a-form-item
          :label="t('rackDiagram.rackName')"
          :required="true"
          :validateStatus="rackFormErrors.name ? 'error' : ''"
          :help="rackFormErrors.name"
        >
          <a-input v-model:value="rackForm.name" :placeholder="t('rackDiagram.rackNamePlaceholder')" />
        </a-form-item>
        <a-form-item :label="t('rackDiagram.rackLocation')">
          <a-input v-model:value="rackForm.location" :placeholder="t('rackDiagram.rackLocationPlaceholder')" />
        </a-form-item>
        <a-form-item :label="t('label.created')">
          <a-input :value="formatRackDate(rackForm.createdAt)" disabled />
        </a-form-item>
        <a-form-item
          :label="t('rackDiagram.totalHeight')"
          :required="true"
          :validateStatus="rackFormErrors.totalHeight ? 'error' : ''"
          :help="rackFormErrors.totalHeight"
        >
          <a-input-number
            v-model:value="rackForm.totalHeight"
            :min="10"
            :max="50"
            :step="1"
            :precision="0"
            style="width: 100%"
          />
        </a-form-item>
      </a-form>
    </a-modal>
    <a-modal
      v-model:visible="moveModalVisible"
      :title="t('rackDiagram.moveDeviceTitle')"
      :ok-text="t('label.ok')"
      :cancel-text="t('label.cancel')"
      @ok="submitMoveDevice"
      destroyOnClose
    >
      <div style="margin-bottom: 16px;">
        <InfoCircleOutlined style="color: #1890ff; margin-right: 8px;" />
        <span>{{ t('rackDiagram.moveDeviceDesc1') }} <strong>{{ t('rackDiagram.moveDeviceDesc2') }}</strong>{{ t('rackDiagram.moveDeviceDesc3') }}</span>
      </div>

      <a-form layout="vertical">
        <a-form-item :label="t('rackDiagram.targetRack')">
          <a-select v-model:value="moveTargetRackIndex" :placeholder="t('rackDiagram.selectRack')" style="width: 100%">
            <a-select-option
              v-for="(rack, idx) in parsedRacks"
              :key="idx"
              :value="idx"
              :disabled="idx === moveSourceInfo.rIndex"
            >
              {{ rack.name }} ({{ t('rackDiagram.needGapCheck') }})
            </a-select-option>
          </a-select>
        </a-form-item>
      </a-form>
    </a-modal>
    <a-modal
      v-model:visible="deviceModalVisible"
      :title="t('rackDiagram.deviceConfig')"
      :ok-text="t('label.ok')"
      :cancel-text="t('label.cancel')"
      @ok="submitDeviceModal"
      @cancel="closeDeviceModal"
      :width="624"
      destroyOnClose
    >
      <a-form layout="vertical" class="device-config-form">
        <a-card size="small" class="device-config-section" :title="t('rackDiagram.sectionBasicInfo')">
        <a-form-item
          :label="t('rackDiagram.deviceType')"
          :required="true"
          :validateStatus="deviceFormErrors.type ? 'error' : ''"
          :help="deviceFormErrors.type"
        >
          <a-select v-model:value="deviceForm.type" style="width: 100%" @change="handleTypeChange">
            <a-select-option value="server">{{ t('rackDiagram.deviceTypeServer') }}</a-select-option>
            <a-select-option value="blade">{{ t('rackDiagram.deviceTypeBlade') }}</a-select-option>
            <a-select-option value="switch">{{ t('rackDiagram.deviceTypeSwitch') }}</a-select-option>
            <a-select-option value="router">{{ t('rackDiagram.deviceTypeRouter') }}</a-select-option>
            <a-select-option value="loadbalancer">{{ t('rackDiagram.deviceTypeLoadBalancer') }}</a-select-option>
            <a-select-option value="storage">{{ t('rackDiagram.deviceTypeStorage') }}</a-select-option>
            <a-select-option value="nas">{{ t('rackDiagram.deviceTypeNas') }}</a-select-option>
            <a-select-option value="firewall">{{ t('rackDiagram.deviceTypeFirewall') }}</a-select-option>
            <a-select-option value="monitoring">{{ t('rackDiagram.deviceTypeMonitoring') }}</a-select-option>
            <a-select-option value="kvm">{{ t('rackDiagram.deviceTypeKvm') }}</a-select-option>
            <a-select-option value="cooling">{{ t('rackDiagram.deviceTypeCooling') }}</a-select-option>
            <a-select-option value="ups">UPS</a-select-option>
            <a-select-option value="patch">{{ t('rackDiagram.deviceTypePatch') }}</a-select-option>
            <a-select-option value="pdu">PDU</a-select-option>
            <a-select-option value="blank">{{ t('rackDiagram.deviceTypeBlank') }}</a-select-option>
            <a-select-option value="custom">{{ t('rackDiagram.deviceTypeCustom') }}</a-select-option>
          </a-select>
        </a-form-item>

        <a-form-item
          :label="t('rackDiagram.deviceName')"
          v-if="deviceForm.type !== 'blank'"
          :required="true"
          :validateStatus="deviceFormErrors.label ? 'error' : ''"
          :help="deviceFormErrors.label"
        >
          <a-input
            v-model:value="deviceForm.label"
            :placeholder="t('rackDiagram.deviceNamePlaceholder')"
            allow-clear
          />
        </a-form-item>

        <a-form-item :label="t('rackDiagram.selectInfraAsset')" v-if="deviceForm.type === 'server'">
          <a-select
            v-model:value="deviceForm.sourceRef"
            :loading="inventoryLoading"
            :options="inventoryOptions"
            :show-search="true"
            option-filter-prop="label"
            :placeholder="t('rackDiagram.selectHost')"
            allow-clear
            style="width: 100%"
            @change="handleSourceChange"
          />
        </a-form-item>

        <a-form-item
          :label="t('rackDiagram.customTypeName')"
          v-if="deviceForm.type === 'custom'"
          :required="true"
          :validateStatus="deviceFormErrors.customType ? 'error' : ''"
          :help="deviceFormErrors.customType"
        >
          <a-input v-model:value="deviceForm.customType" :placeholder="t('rackDiagram.customTypePlaceholder')" />
        </a-form-item>

        <a-form-item
          :label="t('rackDiagram.height')"
          :required="true"
          :validateStatus="deviceFormErrors.height ? 'error' : ''"
          :help="deviceFormErrors.height"
        >
        <a-input-number
          v-model:value="deviceForm.height"
          :min="1"
          :max="maxAllowedHeight"
          :precision="0"
          style="width: 100%"
        />
        <div style="font-size: 12px; color: #888; margin-top: 4px;">
          <InfoCircleOutlined style="margin-right: 4px;" />
          {{ t('rackDiagram.maxAllowedHeightPrefix') }} <strong>{{ maxAllowedHeight }}U</strong> {{ t('rackDiagram.maxAllowedHeightSuffix') }}
        </div>
      </a-form-item>

        </a-card>

        <a-card size="small" class="device-config-section" :title="t('rackDiagram.hardwareInfo')">
        <a-form-item :label="t('rackDiagram.hardwareInfo')" class="device-config-section-form-item">
          <div class="device-hardware-form">
            <div class="device-hardware-form-row">
              <label>{{ t('rackDiagram.vendor') }}</label>
              <a-auto-complete
                v-model:value="deviceForm.hardwareInfo.vendor"
                :options="hardwareVendorOptions"
                :filter-option="filterHardwareVendorOption"
                :placeholder="t('rackDiagram.vendorPlaceholder')"
                style="width: 100%"
              />
            </div>
            <div class="device-hardware-form-row">
              <label>{{ t('rackDiagram.model') }}</label>
              <a-input
                v-model:value="deviceForm.hardwareInfo.model"
                :maxlength="128"
                :placeholder="t('rackDiagram.modelPlaceholder')"
              />
            </div>
            <div class="device-hardware-form-row">
              <label>{{ t('rackDiagram.serial') }}</label>
              <a-input
                v-model:value="deviceForm.hardwareInfo.serial"
                :maxlength="128"
                :placeholder="t('rackDiagram.serialPlaceholder')"
              />
            </div>
            <div class="device-hardware-form-row">
              <label>{{ t('rackDiagram.assetNo') }}</label>
              <a-input
                v-model:value="deviceForm.hardwareInfo.assetNo"
                :maxlength="128"
                :placeholder="t('rackDiagram.assetNoPlaceholder')"
              />
            </div>
            <div class="device-hardware-form-row">
              <label>{{ t('rackDiagram.purchaseDate') }}</label>
              <a-date-picker
                v-model:value="deviceForm.hardwareInfo.purchaseDate"
                value-format="YYYY-MM-DD"
                style="width: 100%"
              />
            </div>
            <div class="device-hardware-form-row">
              <label>{{ t('rackDiagram.maintenanceEndDate') }}</label>
              <a-date-picker
                v-model:value="deviceForm.hardwareInfo.maintenanceEndDate"
                value-format="YYYY-MM-DD"
                style="width: 100%"
              />
            </div>
          </div>
        </a-form-item>

        </a-card>

        <a-card size="small" class="device-config-section" :title="t('rackDiagram.deviceSpecs')">
        <a-form-item :label="t('rackDiagram.deviceSpecs')" class="device-config-section-form-item">
          <div class="quick-links-editor">
            <a-table
              size="small"
              :pagination="false"
              :data-source="deviceSpecRows"
              :columns="deviceSpecColumns"
              row-key="id"
            >
              <template #bodyCell="{ column, record, index }">
                <template v-if="column.key === 'key'">
                  <a-select
                    v-model:value="record.key"
                    :options="deviceSpecKeyOptions"
                    :show-search="true"
                    option-filter-prop="value"
                    :filter-option="filterDeviceSpecKeyOption"
                    :placeholder="t('rackDiagram.deviceSpecKeyPlaceholder')"
                    style="width: 100%"
                  />
                </template>
                <template v-else-if="column.key === 'value'">
                  <span v-if="isNumericDeviceSpecRow(record)" class="device-spec-number-wrap">
                    <a-input-number
                      :value="getSpecNumericEditValue(record)"
                      :min="0"
                      :precision="getSpecNumericPrecision(record)"
                      :placeholder="t('rackDiagram.deviceSpecValuePlaceholder')"
                      @change="value => setSpecNumericEditValue(record, value)"
                    />
                    <span v-if="getDeviceSpecUnit(record)" class="device-spec-unit">{{ getDeviceSpecUnit(record) }}</span>
                  </span>
                  <a-input
                    v-else
                    v-model:value="record.value"
                    :placeholder="t('rackDiagram.deviceSpecValuePlaceholder')"
                  />
                </template>
                <template v-else-if="column.key === 'action'">
                  <a-button
                    size="small"
                    type="text"
                    danger
                    @click="removeDeviceSpecRow(index)"
                  >
                    <template #icon><DeleteOutlined /></template>
                  </a-button>
                </template>
              </template>
            </a-table>
            <a-button size="small" type="dashed" class="quick-links-add-btn" @click="addDeviceSpecRow">
              <template #icon><PlusOutlined /></template>
              {{ t('rackDiagram.addSpecRow') }}
            </a-button>
          </div>
        </a-form-item>

        </a-card>

        <a-card size="small" class="device-config-section" :title="t('rackDiagram.deviceMemo')">
        <a-form-item :label="t('rackDiagram.deviceMemo')" class="device-config-section-form-item">
          <a-textarea
            v-model:value="deviceForm.memo"
            :rows="3"
            :placeholder="t('rackDiagram.deviceMemoPlaceholder')"
          />
        </a-form-item>
        </a-card>

        <a-card size="small" class="device-config-section" :title="t('rackDiagram.quickLinks')">
        <a-form-item
          class="device-config-section-form-item"
          :label="t('rackDiagram.quickLinks')"
          :validateStatus="quickLinksError ? 'error' : ''"
          :help="quickLinksError"
        >
          <div class="quick-links-editor">
            <a-table
              size="small"
              :pagination="false"
              :data-source="quickLinkRows"
              :columns="quickLinkColumns"
              row-key="key"
            >
              <template #bodyCell="{ column, record, index }">
                <template v-if="column.key === 'label'">
                  <a-input
                    v-model:value="record.label"
                    :placeholder="t('rackDiagram.quickLinkNamePlaceholder')"
                    @change="quickLinksError = ''"
                  />
                </template>
                <template v-else-if="column.key === 'url'">
                  <a-input
                    v-model:value="record.url"
                    :placeholder="t('rackDiagram.quickLinkUrlPlaceholder')"
                    @change="quickLinksError = ''"
                  />
                </template>
                <template v-else-if="column.key === 'action'">
                  <a-button
                    size="small"
                    type="text"
                    danger
                    @click="removeQuickLinkRow(index)"
                  >
                    <template #icon><DeleteOutlined /></template>
                  </a-button>
                </template>
              </template>
            </a-table>
            <a-button size="small" type="dashed" class="quick-links-add-btn" @click="addQuickLinkRow">
              <template #icon><PlusOutlined /></template>
              {{ t('rackDiagram.quickLinkAddRow') }}
            </a-button>
          </div>
        </a-form-item>
        </a-card>
      </a-form>
    </a-modal>
    <a-modal
      v-model:visible="assetLinkModalVisible"
      :title="t('rackDiagram.changeInfraAsset')"
      :ok-text="t('label.ok')"
      :cancel-text="t('label.cancel')"
      @ok="submitAssetLinkModal"
      @cancel="closeAssetLinkModal"
      destroyOnClose
    >
      <a-alert
        :message="t('rackDiagram.changeInfraAssetHelp')"
        type="info"
        show-icon
        style="margin-bottom: 12px;"
      />
      <a-select
        v-model:value="assetLinkDraft"
        :loading="inventoryLoading"
        :options="inventoryOptions"
        :show-search="true"
        option-filter-prop="label"
        :placeholder="t('rackDiagram.selectHost')"
        allow-clear
        style="width: 100%"
        @change="handleAssetLinkDraftChange"
      />
    </a-modal>
    <a-modal
      v-model:visible="hostVmModalVisible"
      :title="hostVmModalTitle"
      :footer="null"
      width="760px"
      destroyOnClose
    >
      <a-spin :spinning="hostVmLoading">
        <div class="host-vm-scroll-area">
        <div v-if="!hostVmLoading && !filteredHostVmList.length" class="host-vm-empty-wrap">
          <div class="ant-empty ant-empty-normal">
            <div class="ant-empty-image">
              <svg class="ant-empty-img-simple" width="64" height="41" viewBox="0 0 64 41">
                <g transform="translate(0 1)" fill="none" fill-rule="evenodd">
                  <ellipse class="ant-empty-img-simple-ellipse" fill="#F5F5F5" cx="32" cy="33" rx="32" ry="7"></ellipse>
                  <g class="ant-empty-img-simple-g" fill-rule="nonzero" stroke="#D9D9D9">
                    <path d="M55 12.76L44.854 1.258C44.367.474 43.656 0 42.907 0H21.093c-.749 0-1.46.474-1.947 1.257L9 12.761V22h46v-9.24z"></path>
                    <path d="M41.613 15.931c0-1.605.994-2.93 2.227-2.931H55v18.137C55 33.26 53.68 35 52.05 35h-40.1C10.32 35 9 33.259 9 31.137V13h11.16c1.233 0 2.227 1.323 2.227 2.928v.022c0 1.605 1.005 2.901 2.237 2.901h14.752c1.232 0 2.237-1.308 2.237-2.913v-.007z" fill="#FAFAFA" class="ant-empty-img-simple-path"></path>
                  </g>
                </g>
              </svg>
            </div>
            <p class="ant-empty-description">{{ t('label.no.data') }}</p>
          </div>
        </div>
        <div v-else-if="!hostVmLoading" class="host-vm-grid">
          <div
            v-for="vm in filteredHostVmList"
            :key="vm.id"
            class="host-vm-card"
            :class="{ 'host-vm-card-inactive': !isRunningVm(vm) }"
            @click="goToVmDetail(vm)"
          >
            <div class="host-vm-icon">
              <font-awesome-icon :icon="['fab', getVmOsLogo(vm)]" size="lg" />
            </div>
            <a-tooltip :title="vm.displayname || vm.name || vm.id">
              <div class="host-vm-name">{{ vm.displayname || vm.name || vm.id }}</div>
            </a-tooltip>
            <div class="host-vm-meta">{{ vm.state || '-' }} / {{ vm.hypervisor || '-' }}</div>
            <a-tooltip :title="getVmPrimaryIpText(vm)">
              <div class="host-vm-ip">{{ getVmPrimaryIpText(vm) }}</div>
            </a-tooltip>
          </div>
        </div>
        </div>
      </a-spin>
    </a-modal>

  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed, watch, onBeforeUnmount, nextTick, getCurrentInstance } from 'vue'
import { message } from 'ant-design-vue'
import html2canvas from 'html2canvas'
import { api } from '@/api'
import { useRouter } from 'vue-router'
import { useStore } from 'vuex'
import RackListCardIcon from './components/RackListCardIcon.vue'
import {
  CopyOutlined,
  EditOutlined,
  SettingOutlined,
  DeleteOutlined,
  InfoCircleOutlined,
  PlusOutlined,
  FileTextOutlined,
  UploadOutlined,
  CameraOutlined,
  SaveOutlined,
  ExportOutlined,
  LinkOutlined,
  LaptopOutlined,
  AppstoreOutlined,
  MoreOutlined,
  UnorderedListOutlined,
  CalendarOutlined,
  EnvironmentOutlined,
  CloseOutlined,
  PieChartOutlined,
  DatabaseOutlined,
  InboxOutlined,
  DownOutlined,
  FullscreenOutlined,
  CompressOutlined
} from '@ant-design/icons-vue'

// 전역 상태
const loading = ref(false)
const saving = ref(false)
const isExpanded = ref(false)
const zoomLevel = ref(0.65)
const AUTO_ZOOM_MIN = 0.65
const NORMAL_ZOOM_LEVEL = 0.96
const AUTO_ZOOM_MAX = NORMAL_ZOOM_LEVEL
const INSPECTOR_ZOOM_MIN = NORMAL_ZOOM_LEVEL * 0.4
const INSPECTOR_FIT_GUTTER = 8
const isAutoZoomEnabled = ref(true)
const rackMainPaneRef = ref(null)
const rackDetailLayoutRef = ref(null)
const rackDiagramRootRef = ref(null)
const rackSidePaneSlotRef = ref(null)
const sidePaneAvailableHeight = ref(0)
let resizeDebounceTimer = null
let zoomRafId = 0
let zoomApplyRafId = 0
let zoomTransitionRafId = 0
let sidePaneHeightRafId = 0
let rackMainPaneResizeObserver = null
let sidePaneViewportResizeObserver = null
let rackScrollContainer = null
let expandedLayoutZoomTimer = null
const router = useRouter()
const store = useStore()
const { proxy, emit } = getCurrentInstance()
const t = (key, args) => proxy?.$t ? proxy.$t(key, args) : key
const isDarkMode = computed(() => !!store.getters.darkMode)
const RACK_UNIT_HEIGHT = 52
const MAX_RACK_COUNT = 20
// The backend accepts up to 1 MiB while rackml_config.content uses MEDIUMTEXT.
const MAX_RACK_LAYOUT_BYTES = 1024 * 1024

const getRackLayoutSize = (racks) => new Blob([JSON.stringify(racks)]).size

const validateRackLayoutSize = (racks) => {
  if (getRackLayoutSize(racks) <= MAX_RACK_LAYOUT_BYTES) return true

  message.error(t('rackDiagram.msg.layoutSizeExceeded', { max: '1MB' }))
  return false
}

const validateRackCount = (racks) => {
  if (racks.length <= MAX_RACK_COUNT) return true

  message.warning(t('rackDiagram.msg.rackCountExceeded', { max: MAX_RACK_COUNT }))
  return false
}

const validateRackLayoutLimits = (racks) => validateRackCount(racks) && validateRackLayoutSize(racks)

const toggleExpandedView = () => {
  isExpanded.value = !isExpanded.value
  emit('toggle-expand', isExpanded.value)

  if (expandedLayoutZoomTimer) clearTimeout(expandedLayoutZoomTimer)
  nextTick(() => {
    // 요약 패널 전환이 끝난 실제 너비를 기준으로 상세 랙 배율을 다시 맞춥니다.
    expandedLayoutZoomTimer = setTimeout(() => {
      expandedLayoutZoomTimer = null
      if (showRackList.value) return
      isAutoZoomEnabled.value = true
      applyResponsiveZoom(true, true)
    }, 320)
  })
}

// 슬라이더 및 입력창과 연동할 퍼센트 단위 변수
const zoomPercent = computed({
  get: () => Math.round((zoomLevel.value / NORMAL_ZOOM_LEVEL) * 100),
  set: (val) => {
    isAutoZoomEnabled.value = false
    // 최소 40% ~ 최대 150% 범위 제한
    if (val < 40) val = 40
    if (val > 150) val = 150
    zoomLevel.value = (val / 100) * NORMAL_ZOOM_LEVEL
  }
})
const zoomPercentUi = ref(zoomPercent.value)

const clampZoomPercent = (val) => {
  let next = Number(val || 0)
  if (!Number.isFinite(next)) next = zoomPercent.value
  if (next < 40) next = 40
  if (next > 150) next = 150
  return Math.round(next)
}

const scheduleZoomApply = (percent) => {
  if (zoomTransitionRafId) {
    cancelAnimationFrame(zoomTransitionRafId)
    zoomTransitionRafId = 0
  }
  if (zoomApplyRafId) cancelAnimationFrame(zoomApplyRafId)
  zoomApplyRafId = requestAnimationFrame(() => {
    zoomPercent.value = percent
  })
}

const animateZoomLevel = (target, duration = 360) => {
  if (zoomTransitionRafId) cancelAnimationFrame(zoomTransitionRafId)

  const start = Number(zoomLevel.value || target)
  const distance = target - start
  if (Math.abs(distance) < 0.005) {
    zoomLevel.value = target
    zoomTransitionRafId = 0
    return
  }

  const startedAt = performance.now()
  let lastAppliedAt = 0
  const step = (now) => {
    const progress = Math.min(1, (now - startedAt) / duration)
    const eased = 1 - Math.pow(1 - progress, 3)

    // CSS zoom의 연속 재배치를 줄이기 위해 약 30fps로 제한합니다.
    if (now - lastAppliedAt >= 32 || progress === 1) {
      zoomLevel.value = start + distance * eased
      lastAppliedAt = now
    }

    if (progress < 1) {
      zoomTransitionRafId = requestAnimationFrame(step)
    } else {
      zoomLevel.value = target
      zoomTransitionRafId = 0
    }
  }

  zoomTransitionRafId = requestAnimationFrame(step)
}

const onZoomSliderChange = (val) => {
  zoomPercentUi.value = clampZoomPercent(val)
  scheduleZoomApply(zoomPercentUi.value)
}

const onZoomInputChange = (val) => {
  zoomPercentUi.value = clampZoomPercent(val)
}

const commitZoomUi = () => {
  zoomPercentUi.value = clampZoomPercent(zoomPercentUi.value)
  zoomPercent.value = zoomPercentUi.value
}

const zoomWrapperStyle = computed(() => {
  const z = Number(zoomLevel.value || 1)
  return {
    zoom: z
  }
})

const sidePaneSlotStyle = computed(() => {
  if (!sidePaneAvailableHeight.value) return {}
  const height = `${sidePaneAvailableHeight.value}px`
  return { height, maxHeight: height }
})

// 데이터 모델
const parsedRacks = ref([])
const showRackList = ref(true)
const rackListViewMode = ref('card')
const selectedRackIndex = ref(-1)
const selectedDeviceRackIndex = ref(-1)
const selectedDeviceItemIndex = ref(-1)
const visibleRackEntries = computed(() => {
  if (selectedRackIndex.value > -1 && parsedRacks.value[selectedRackIndex.value]) {
    return [{ rack: parsedRacks.value[selectedRackIndex.value], index: selectedRackIndex.value }]
  }
  return parsedRacks.value.map((rack, index) => ({ rack, index }))
})
const selectedDevice = computed(() => {
  if (selectedDeviceRackIndex.value < 0 || selectedDeviceItemIndex.value < 0) return null
  const rack = parsedRacks.value[selectedDeviceRackIndex.value]
  const item = rack?.items?.[selectedDeviceItemIndex.value]
  if (!item || item.type === 'gap') return null
  return item
})
const selectedDeviceHost = ref(null)
const deviceInfoActiveTab = ref('summary')
const activeInlineField = ref('')
const selectedDeviceDraft = reactive({
  label: '',
  height: 1,
  startU: 1,
  memo: '',
  sourceRef: null,
  customType: '',
  hardwareInfo: {
    vendor: '',
    model: '',
    serial: '',
    assetNo: '',
    purchaseDate: '',
    maintenanceEndDate: ''
  },
  specs: [],
  quickLinks: []
})
const selectedDeviceDraftBaseline = ref('')
const assetLinkModalVisible = ref(false)
const assetLinkDraft = ref(undefined)
const structuredMemoRows = ref([])
const freeMemoText = ref('')
const deviceSpecRows = ref([])
const LINKED_ASSET_MARKER = '[LinkedAsset]'
const stripLinkedAssetMemo = (memo) => String(memo || '')
  .split('\n')
  .filter(line => !line.trim().startsWith(LINKED_ASSET_MARKER))
  .join('\n')
  .trim()

const makeMemoRow = (key = '', value = '') => ({
  id: `${Date.now()}-${Math.random().toString(36).slice(2)}`,
  key,
  value
})

const createEmptyHardwareInfo = () => ({
  vendor: '',
  model: '',
  serial: '',
  assetNo: '',
  purchaseDate: '',
  maintenanceEndDate: ''
})

const hardwareInfoFields = [
  { key: 'vendor', labelKey: 'rackDiagram.vendor' },
  { key: 'model', labelKey: 'rackDiagram.model' },
  { key: 'serial', labelKey: 'rackDiagram.serial' },
  { key: 'assetNo', labelKey: 'rackDiagram.assetNo' },
  { key: 'purchaseDate', labelKey: 'rackDiagram.purchaseDate', type: 'date' },
  { key: 'maintenanceEndDate', labelKey: 'rackDiagram.maintenanceEndDate', type: 'date' }
]

const hardwareVendorOptions = [
  'Dell Technologies',
  'Dell EMC',
  'HPE',
  'Hewlett Packard Enterprise',
  'Lenovo',
  'Cisco',
  'Supermicro',
  'IBM',
  'Oracle',
  'Huawei',
  'NetApp',
  'Hitachi Vantara',
  'Fujitsu',
  'Inspur',
  'NEC',
  'QNAP',
  'Synology',
  'APC',
  'Eaton',
  'Vertiv'
].map(value => ({ value }))

const filterHardwareVendorOption = (input, option) => {
  return String(option?.value || '').toLowerCase().includes(String(input || '').toLowerCase())
}

const deviceSpecKeyOptionKeys = [
  // Compute
  'rackDiagram.specCpuCores',
  'rackDiagram.specCpuMhz',
  'rackDiagram.specCpuGhz',
  'rackDiagram.specCpuSocketCount',
  'rackDiagram.specGpuCount',
  'rackDiagram.specGpuMemoryGib',
  // Memory
  'rackDiagram.specMemoryMb',
  'rackDiagram.specMemoryGb',
  'rackDiagram.specMemoryGib',
  'rackDiagram.specMemoryTb',
  'rackDiagram.specMemoryTib',
  // Capacity / storage
  'rackDiagram.specDiskGb',
  'rackDiagram.specDiskGib',
  'rackDiagram.specDiskTb',
  'rackDiagram.specDiskTib',
  'rackDiagram.specStorageGb',
  'rackDiagram.specStorageGib',
  'rackDiagram.specStorageTb',
  'rackDiagram.specStorageTib',
  'rackDiagram.specCapacityGb',
  'rackDiagram.specCapacityGib',
  'rackDiagram.specCapacityTb',
  'rackDiagram.specCapacityTib',
  'rackDiagram.specDriveBayCount',
  // Network
  'rackDiagram.specNicCount',
  'rackDiagram.specNetworkPortCount',
  'rackDiagram.specPortCount',
  'rackDiagram.specThroughputMbps',
  'rackDiagram.specThroughputGbps',
  'rackDiagram.specThroughputTbps',
  // Power / UPS
  'rackDiagram.specPowerW',
  'rackDiagram.specPowerKw',
  'rackDiagram.specVoltageV',
  'rackDiagram.specCurrentA',
  'rackDiagram.specCapacityVa',
  'rackDiagram.specCapacityKva',
  'rackDiagram.specBatteryRuntimeMin',
  'rackDiagram.specBatteryCapacityWh',
  // Cooling
  'rackDiagram.specAirflowCfm',
  'rackDiagram.specFanRpm',
  'rackDiagram.specFanCount',
  // System
  'rackDiagram.specFirmware',
  'rackDiagram.specOs',
  'rackDiagram.specHypervisor'
]

const textDeviceSpecKeyOptionKeys = new Set([
  'rackDiagram.specFirmware',
  'rackDiagram.specOs',
  'rackDiagram.specHypervisor'
])

const deviceSpecKeyOptions = computed(() => deviceSpecKeyOptionKeys.map(key => ({ value: t(key) })))

const filterDeviceSpecKeyOption = (input, option) => {
  return String(option?.value || '').toLowerCase().includes(String(input || '').toLowerCase())
}

const getDeviceSpecMetaByLabel = (label) => {
  const normalized = String(label || '').trim()
  if (!normalized) return null
  const labelKey = deviceSpecKeyOptionKeys.find(key => t(key) === normalized)
  if (!labelKey) return null
  const translated = t(labelKey)
  const unitMatch = translated.match(/\(([^)]+)\)/)
  return {
    labelKey,
    numeric: !textDeviceSpecKeyOptionKeys.has(labelKey),
    unit: unitMatch ? unitMatch[1].trim() : ''
  }
}

const isNumericDeviceSpecRow = (row) => {
  return !!getDeviceSpecMetaByLabel(row?.key)?.numeric
}

const getDeviceSpecUnit = (row) => {
  return getDeviceSpecMetaByLabel(row?.key)?.unit || ''
}

const getSpecNumericPrecision = (row) => {
  const unit = getDeviceSpecUnit(row).toLowerCase()
  return ['ghz', 'kw', 'kva', 'tbps'].includes(unit) ? 2 : 0
}

const stripDeviceSpecUnit = (row) => {
  const value = String(row?.value || '').trim()
  const unit = getDeviceSpecUnit(row)
  if (!unit) return value
  return value.replace(new RegExp(`\\s*${unit.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`, 'i'), '').trim()
}

const getSpecNumericEditValue = (row) => {
  const numericValue = stripDeviceSpecUnit(row).match(/^\d+(\.\d+)?/)
  return numericValue ? Number(numericValue[0]) : undefined
}

const setSpecNumericEditValue = (row, value) => {
  row.value = value === null || value === undefined || value === '' ? '' : String(value)
  markInlineDraftChanged()
}

const formatDeviceSpecValue = (row) => {
  const key = String(row.key || '').trim()
  const value = String(row.value || '').trim()
  if (!key || !isNumericDeviceSpecRow(row)) return value

  const numericMatch = stripDeviceSpecUnit(row).match(/^\d+(\.\d+)?/)
  const numericValue = numericMatch ? numericMatch[0] : ''
  if (!numericValue) return ''

  const unit = getDeviceSpecUnit(row)
  return unit ? `${numericValue}${unit}` : numericValue
}

const normalizeHardwareInfo = (hardwareInfo) => {
  const source = hardwareInfo && typeof hardwareInfo === 'object' ? hardwareInfo : {}
  return {
    vendor: String(source.vendor || '').trim(),
    model: String(source.model || '').trim(),
    serial: String(source.serial || '').trim(),
    assetNo: String(source.assetNo || '').trim(),
    purchaseDate: String(source.purchaseDate || '').trim(),
    maintenanceEndDate: String(source.maintenanceEndDate || '').trim()
  }
}

const assignHardwareInfo = (target, source) => {
  const normalized = normalizeHardwareInfo(source)
  Object.keys(createEmptyHardwareInfo()).forEach(key => {
    target[key] = normalized[key] || ''
  })
}

const validateHardwareInfo = (hardwareInfo) => {
  const normalized = normalizeHardwareInfo(hardwareInfo)
  const lengthFields = [
    { key: 'vendor', labelKey: 'rackDiagram.vendor' },
    { key: 'model', labelKey: 'rackDiagram.model' },
    { key: 'serial', labelKey: 'rackDiagram.serial' },
    { key: 'assetNo', labelKey: 'rackDiagram.assetNo' }
  ]
  const invalid = lengthFields.find(field => normalized[field.key].length > 128)
  if (invalid) {
    message.warning(t('rackDiagram.msg.hardwareFieldMax', { field: t(invalid.labelKey) }))
    return null
  }
  if (normalized.purchaseDate && normalized.maintenanceEndDate && normalized.purchaseDate >= normalized.maintenanceEndDate) {
    message.warning(t('rackDiagram.msg.purchaseDateBeforeMaintenanceEndDate'))
    return null
  }
  return normalized
}

const parseDeviceMemo = (memo) => {
  const rows = []
  const freeLines = []
  stripLinkedAssetMemo(memo)
    .split('\n')
    .map(line => line.trim())
    .filter(Boolean)
    .forEach(line => {
      const eqIndex = line.indexOf('=')
      const colonIndex = line.indexOf(':')
      const splitIndex = eqIndex > -1 ? eqIndex : colonIndex
      if (splitIndex > 0) {
        rows.push(makeMemoRow(line.slice(0, splitIndex).trim(), line.slice(splitIndex + 1).trim()))
      } else {
        freeLines.push(line)
      }
    })
  return { rows, freeText: freeLines.join('\n') }
}

const normalizeSpecRows = (specs) => {
  if (!Array.isArray(specs)) return []
  return specs
    .map(row => makeMemoRow(String(row?.key || '').trim(), String(row?.value || '').trim()))
    .filter(row => row.key || row.value)
}

const getDeviceSpecRows = (device) => {
  const specs = normalizeSpecRows(device?.specs)
  if (specs.length) return specs
  return parseDeviceMemo(device?.memo || '').rows
}

const getDeviceFreeMemo = (device) => {
  if (Array.isArray(device?.specs)) return stripLinkedAssetMemo(device?.memo || '')
  return parseDeviceMemo(device?.memo || '').freeText
}

const syncSideMemoFields = (device) => {
  structuredMemoRows.value = getDeviceSpecRows(device)
  freeMemoText.value = getDeviceFreeMemo(device)
}

const visibleDeviceSpecRows = computed(() => {
  if (activeInlineField.value === 'specs') return selectedDeviceDraft.specs
  return selectedDeviceDraft.specs.filter(row => String(row.key || '').trim() || String(row.value || '').trim())
})

const cloneQuickLinksForEdit = (links) => {
  return (Array.isArray(links) ? links : []).map(link => makeQuickLinkRow(link.label || '', link.url || ''))
}

const getCurrentDeviceStartU = (rack, iIndex) => {
  if (!rack || iIndex < 0) return 1
  const item = rack.items?.[iIndex]
  if (!item) return 1
  const aboveHeight = rack.items.slice(0, iIndex).reduce((sum, current) => sum + Number(current.height || 0), 0)
  const topU = Number(rack.totalHeight || 0) - aboveHeight
  return Math.max(1, topU - Number(item.height || 0) + 1)
}

const selectedRackTotalHeight = computed(() => {
  const rack = parsedRacks.value[selectedDeviceRackIndex.value]
  return Number(rack?.totalHeight || 1)
})

const sidePanelMaxAllowedHeight = computed(() => {
  const rack = parsedRacks.value[selectedDeviceRackIndex.value]
  const item = rack?.items?.[selectedDeviceItemIndex.value]
  if (!rack || !item) return 1
  let physicalMax = Number(item.height || 1)
  const nextItem = rack.items[selectedDeviceItemIndex.value + 1]
  if (nextItem?.type === 'gap') physicalMax += Number(nextItem.height || 0)
  return Math.max(1, physicalMax)
})

const getSideDraftSnapshot = () => ({
  label: String(selectedDeviceDraft.label || '').trim(),
  height: Number(selectedDeviceDraft.height || 1),
  startU: Number(selectedDeviceDraft.startU || 1),
  memo: stripLinkedAssetMemo(selectedDeviceDraft.memo),
  sourceRef: selectedDeviceDraft.sourceRef || null,
  customType: String(selectedDeviceDraft.customType || '').trim(),
  hardwareInfo: normalizeHardwareInfo(selectedDeviceDraft.hardwareInfo),
  specs: getCleanSpecRows(selectedDeviceDraft.specs),
  quickLinks: cloneQuickLinksForEdit(selectedDeviceDraft.quickLinks)
    .map(link => ({ label: String(link.label || '').trim(), url: String(link.url || '').trim() }))
    .filter(link => link.label || link.url)
})

const syncSelectedDeviceDraft = (device, resetActive = true) => {
  const rack = parsedRacks.value[selectedDeviceRackIndex.value]
  selectedDeviceDraft.label = device?.label || ''
  selectedDeviceDraft.height = Number(device?.height || 1)
  selectedDeviceDraft.startU = getCurrentDeviceStartU(rack, selectedDeviceItemIndex.value)
  selectedDeviceDraft.memo = getDeviceFreeMemo(device)
  selectedDeviceDraft.sourceRef = device?.sourceRef || null
  selectedDeviceDraft.customType = device?.customType || ''
  assignHardwareInfo(selectedDeviceDraft.hardwareInfo, device?.hardwareInfo)
  selectedDeviceDraft.specs = getDeviceSpecRows(device).length ? getDeviceSpecRows(device) : []
  selectedDeviceDraft.quickLinks = cloneQuickLinksForEdit(getQuickLinks(device))
  selectedDeviceDraftBaseline.value = JSON.stringify(getSideDraftSnapshot())
  if (resetActive) activeInlineField.value = ''
}

const sidePanelDevice = computed(() => ({
  ...(selectedDevice.value || {}),
  label: selectedDeviceDraft.label,
  height: selectedDeviceDraft.height,
  customType: selectedDeviceDraft.customType,
  sourceRef: selectedDeviceDraft.sourceRef,
  hardwareInfo: selectedDeviceDraft.hardwareInfo,
  specs: selectedDeviceDraft.specs,
  memo: selectedDeviceDraft.memo,
  quickLinks: selectedDeviceDraft.quickLinks
}))

const selectedDeviceCustomLinks = computed(() => {
  if (selectedDeviceRackIndex.value < 0 || selectedDeviceItemIndex.value < 0) return []
  const rack = parsedRacks.value[selectedDeviceRackIndex.value]
  const item = rack?.items?.[selectedDeviceItemIndex.value]
  const merged = [...getQuickLinks(item), ...getQuickLinks({ quickLinks: selectedDeviceDraft.quickLinks })]
  const seen = new Set()
  return merged.filter(link => {
    const key = `${String(link.label || '').trim()}|${String(link.url || '').trim()}`
    if (!link.url || seen.has(key)) return false
    seen.add(key)
    return true
  })
})

const draftPositionLabel = computed(() => {
  const startU = Number(selectedDeviceDraft.startU || 1)
  const height = Number(selectedDeviceDraft.height || 1)
  return height > 1 ? `${startU}-${startU + height - 1}U` : `${startU}U`
})

const finishInlineEdit = () => {
  if (!activeInlineField.value) return
  applyInlineDeviceChangesToRack()
  activeInlineField.value = ''
}

const markInlineDraftChanged = () => {
  // v-model already updates the draft. Keep the editor open until explicit save/section switch.
  isDirty.value = true
}

const activateInlineField = (field) => {
  if (activeInlineField.value === field) {
    finishInlineEdit()
    return
  }
  if (activeInlineField.value) applyInlineDeviceChangesToRack()
  activeInlineField.value = field
  if (field === 'specs' && !selectedDeviceDraft.specs.length) addInlineSpecRow()
  if (field === 'quickLinks' && !selectedDeviceDraft.quickLinks.length) addInlineQuickLink()
}

const addInlineSpecRow = () => {
  selectedDeviceDraft.specs.push(makeMemoRow())
  markInlineDraftChanged()
}

const removeInlineSpecRow = (idx) => {
  selectedDeviceDraft.specs.splice(idx, 1)
  if (!selectedDeviceDraft.specs.length) addInlineSpecRow()
  markInlineDraftChanged()
}

const addInlineQuickLink = () => {
  selectedDeviceDraft.quickLinks.push(makeQuickLinkRow())
  applyInlineDeviceChangesToRack()
}

const deviceTypeLabelKeys = {
  server: 'rackDiagram.deviceTypeServer',
  blade: 'rackDiagram.deviceTypeBlade',
  switch: 'rackDiagram.deviceTypeSwitch',
  router: 'rackDiagram.deviceTypeRouter',
  loadbalancer: 'rackDiagram.deviceTypeLoadBalancer',
  storage: 'rackDiagram.deviceTypeStorage',
  nas: 'rackDiagram.deviceTypeNas',
  firewall: 'rackDiagram.deviceTypeFirewall',
  monitoring: 'rackDiagram.deviceTypeMonitoring',
  kvm: 'rackDiagram.deviceTypeKvm',
  cooling: 'rackDiagram.deviceTypeCooling',
  patch: 'rackDiagram.deviceTypePatch',
  pdu: 'rackDiagram.deviceTypePdu',
  ups: 'rackDiagram.deviceTypeUps',
  blank: 'rackDiagram.deviceTypeBlank',
  custom: 'rackDiagram.deviceTypeCustom'
}
const selectedDeviceTypeLabel = computed(() => {
  const type = selectedDevice.value?.type
  return type ? t(deviceTypeLabelKeys[type] || 'rackDiagram.deviceTypeCustom') : '-'
})
const selectedDeviceIp = computed(() => selectedDeviceHost.value?.ipaddress || '')
const selectedDeviceStatusLabel = computed(() => {
  const state = selectedDeviceHostInfo.value.find(row => row.label === t('label.state'))?.value
  return state && state !== '-' ? state : ''
})
const selectedDeviceHostInfo = computed(() => {
  const host = selectedDeviceHost.value
  if (!host) return []
  const toStateLabel = (state) => {
    const s = String(state || '').toLowerCase()
    if (s === 'up' || s === 'running') return t('label.running')
    if (s === 'enabled' || s === 'normal') return t('label.enabled')
    if (s === 'disabled') return t('label.disabled')
    return state || '-'
  }
  const toPowerLabel = (state) => {
    const s = String(state || '').toLowerCase()
    if (!s) return '-'
    if (s === 'disabled' || s === 'off' || s === 'inactive') return t('label.disabled')
    if (s === 'enabled' || s === 'on' || s === 'active') return t('label.enabled')
    return state
  }
  const toHaLabel = (v) => {
    const s = String(v ?? '').toLowerCase()
    if (s === 'true' || s === 'enabled' || s === 'yes' || s === '1') return t('label.enabled')
    if (s === 'false' || s === 'disabled' || s === 'no' || s === '0') return t('label.disabled')
    return '-'
  }

  const powerRaw = host?.powerstate || host?.outofbandmanagement?.powerstate || host?.details?.powerstate
  const haRaw = host?.haenable ?? host?.haenabled ?? host?.hahost ?? host?.details?.haenable ?? host?.details?.haenabled ?? host?.details?.hahost
  return [
    { label: t('label.ip'), value: host.ipaddress || '-' },
    { label: t('label.state'), value: toStateLabel(host.state) },
    { label: t('label.resourcestate'), value: toStateLabel(host.resourcestate) },
    { label: t('label.powerstate'), value: toPowerLabel(powerRaw) },
    { label: t('label.ha'), value: toHaLabel(haRaw) },
    { label: t('label.hypervisor'), value: host.hypervisor || '-' }
  ]
})
const selectedDeviceHostInfoCompact = computed(() => {
  const hiddenLabels = new Set([
    t('label.ip'),
    t('label.state'),
    t('label.resourcestate'),
    t('label.powerstate')
  ])
  return selectedDeviceHostInfo.value.filter(row => !hiddenLabels.has(row.label))
})
const selectedDeviceLinkedAsset = computed(() => {
  const sourceRef = selectedDeviceDraft.sourceRef
  if (!sourceRef) return { name: '-', ip: '', copyText: '' }
  const selected = inventoryOptions.value.find(o => o.value === sourceRef)
  if (selected?.meta) {
    const name = selected.meta.name || selected.label || sourceRef
    const ip = selected.meta.ip || ''
    return { name, ip, copyText: ip ? `${name} / ${ip}` : name }
  }
  if (String(sourceRef).startsWith('host:')) {
    const hostId = String(sourceRef).split(':')[1] || ''
    const host = selectedDeviceHost.value || hostCache.value[hostId]
    if (host) {
      const name = host.name || host.hostname || host.id || hostId
      const ip = host.ipaddress || ''
      return { name, ip, copyText: ip ? `${name} / ${ip}` : name }
    }
  }
  return { name: sourceRef, ip: '', copyText: sourceRef }
})
const selectedDeviceLinkedAssetName = computed(() => selectedDeviceLinkedAsset.value.name)
const selectedDeviceLinkedAssetIp = computed(() => selectedDeviceLinkedAsset.value.ip)

const copyTextToClipboard = async (text) => {
  const value = String(text || '').trim()
  if (!value) return
  try {
    await navigator.clipboard.writeText(value)
    message.success(t('message.success.copy.clipboard'))
  } catch (e) {
    const textarea = document.createElement('textarea')
    textarea.value = value
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
    message.success(t('message.success.copy.clipboard'))
  }
}

const openAssetLinkModal = () => {
  assetLinkDraft.value = selectedDeviceDraft.sourceRef || undefined
  if (!inventoryOptions.value.length) buildInventoryOptions()
  assetLinkModalVisible.value = true
}

const closeAssetLinkModal = () => {
  assetLinkModalVisible.value = false
}

const handleAssetLinkDraftChange = (value) => {
  if (!value) return
  const selected = inventoryOptions.value.find(o => o.value === value)
  if (!selected) return
  selectedDeviceDraft.label = selected.meta?.name || selected.label || selectedDeviceDraft.label
}

const submitAssetLinkModal = async () => {
  const before = JSON.stringify(getSideDraftSnapshot())
  selectedDeviceDraft.sourceRef = assetLinkDraft.value || null
  handleAssetLinkDraftChange(selectedDeviceDraft.sourceRef)
  selectedDeviceHost.value = null
  if (selectedDeviceDraft.sourceRef && String(selectedDeviceDraft.sourceRef).startsWith('host:')) {
    const hostId = String(selectedDeviceDraft.sourceRef).split(':')[1] || ''
    if (hostId) {
      try {
        selectedDeviceHost.value = await fetchHostById(hostId)
      } catch (e) {
        selectedDeviceHost.value = null
      }
    }
  }
  applyInlineDeviceChangesToRack()
  if (before !== JSON.stringify(getSideDraftSnapshot())) {
    isDirty.value = true
  }
  closeAssetLinkModal()
}

const openQuickLink = (link) => {
  if (link?.url) window.open(link.url, '_blank')
}

const updateSidePaneAvailableHeight = () => {
  const slot = rackSidePaneSlotRef.value
  if (!slot || showRackList.value || !selectedDevice.value) return

  if (!rackScrollContainer) {
    rackScrollContainer = rackDiagramRootRef.value?.closest('.layout-content') || null
  }

  const slotRect = slot.getBoundingClientRect()
  const scrollRect = rackScrollContainer?.getBoundingClientRect()
  const viewportBottom = Math.min(window.innerHeight, scrollRect?.bottom || window.innerHeight)
  const availableHeight = Math.floor(viewportBottom - slotRect.top - 16)
  const nextHeight = Math.max(240, availableHeight)

  if (Math.abs(nextHeight - sidePaneAvailableHeight.value) > 1) {
    sidePaneAvailableHeight.value = nextHeight
  }
}

const scheduleSidePaneHeightUpdate = () => {
  if (sidePaneHeightRafId) cancelAnimationFrame(sidePaneHeightRafId)
  sidePaneHeightRafId = requestAnimationFrame(() => {
    sidePaneHeightRafId = 0
    updateSidePaneAvailableHeight()
  })
}

const handleSidePaneBeforeEnter = (element) => {
  if (!isAutoZoomEnabled.value) return
  const layoutWidth = rackDetailLayoutRef.value?.clientWidth || 0
  const measuredWidth = Number.parseFloat(window.getComputedStyle(element).width) || 0
  const responsiveWidth = window.matchMedia('(max-width: 980px)').matches
    ? 280
    : Math.min(460, Math.max(320, layoutWidth * 0.42))
  const inspectorWidth = measuredWidth > 1 ? measuredWidth : responsiveWidth
  const targetMainPaneWidth = Math.max(0, layoutWidth - inspectorWidth - 12)
  applyResponsiveZoom(false, true, targetMainPaneWidth)
}

const handleSidePaneAfterEnter = () => {
  nextTick(() => {
    applyResponsiveZoom(false, true)
    scheduleSidePaneHeightUpdate()
  })
}

const handleSidePaneAfterLeave = () => {
  sidePaneAvailableHeight.value = 0
  nextTick(() => applyResponsiveZoom(false, true))
}
// 데이터 변경 여부 추적
const isDirty = ref(false)

// parsedRacks 객체가 깊은 곳까지 변경되는지 감시
watch(parsedRacks, () => {
  isDirty.value = true
}, { deep: true })

watch(zoomPercent, (val) => {
  zoomPercentUi.value = clampZoomPercent(val)
})

watch(selectedDevice, async (device) => {
  nextTick(() => scheduleSidePaneHeightUpdate())
  selectedDeviceHost.value = null
  deviceInfoActiveTab.value = 'summary'
  if (!device) {
    activeInlineField.value = ''
    return
  }
  syncSelectedDeviceDraft(device)
  syncSideMemoFields(device)
  const sourceRef = String(device?.sourceRef || '')
  if (!sourceRef.startsWith('host:')) return
  const hostId = sourceRef.split(':')[1] || ''
  if (!hostId) return
  try {
    const host = await fetchHostById(hostId)
    selectedDeviceHost.value = host || null
  } catch (e) {
    selectedDeviceHost.value = null
  }
})

watch(showRackList, () => {
  nextTick(() => {
    syncRackMainPaneResizeObserver()
    scheduleSidePaneHeightUpdate()
  })
})

// 페이지 이탈 방지 이벤트 핸들러
const handleBeforeUnload = (e) => {
  if (isDirty.value) {
    e.preventDefault()
    e.returnValue = '' // 브라우저 표준 경고창을 띄우기 위한 필수 값
  }
}

const currentZoneId = ref('')
const zoneLoading = ref(false)

const fetchZonesAndRackData = () => {
  zoneLoading.value = true // 필요시 템플릿에 스피너(Loading) 연동 가능

  api('listZones', {}).then(json => {
    const listZones = json.listzonesresponse.zone
    if (listZones && listZones.length > 0) {
      // 'Edge' 타입을 제외한 Zone 목록 필터링
      const filteredZones = listZones.filter(zone => zone.type !== 'Edge')

      if (filteredZones.length > 0) {
        // 첫 번째 Zone의 ID를 현재 Zone으로 설정
        currentZoneId.value = filteredZones[0].id

        // Zone ID를 성공적으로 가져왔으므로 DB에서 랙 구성 조회 시작
        fetchRackData()
      } else {
        message.warning(t('rackDiagram.msg.noAvailableZone'))
      }
    }
  }).catch(error => {
    console.error('Zone 목록 조회 실패:', error)
    message.error(t('rackDiagram.msg.zoneLoadFailed'))
  }).finally(() => {
    zoneLoading.value = false
  })
}

// ----------------------------------------------------------------
// 3. DB에서 랙 구성 불러오기 (GET)
// ----------------------------------------------------------------
const fetchRackData = () => {
  if (!currentZoneId.value) return

  loading.value = true

  api('listRackLayouts', {
    zoneid: currentZoneId.value,
    name: 'default'
  }).then(json => {
    const layouts = json.listracklayoutsresponse.racklayout

    // DB에 저장된 데이터가 있고, 내용(content)이 존재하는 경우
    if (layouts && layouts.length > 0 && layouts[0].content) {
      parsedRacks.value = normalizeLoadedRackLayouts(JSON.parse(layouts[0].content))
    } else {
      // 최초 배포/접속 시에는 임의 랙을 만들지 않는다.
      // 사용자가 명시적으로 "새 랙 추가"를 눌렀을 때만 생성일/위치가 있는 랙을 만든다.
      parsedRacks.value = []
    }
    // 데이터를 방금 불러왔으므로 미저장 상태(isDirty) 초기화 (빈 줄 삭제함)
    nextTick(() => { isDirty.value = false })
  }).catch(error => {
    console.error('랙 데이터 불러오기 실패:', error)
    message.error(t('rackDiagram.msg.rackLoadFailed'))
  }).finally(() => {
    loading.value = false
  })
}

const isLegacyAutoEmptyRack = (rack) => {
  if (!rack || String(rack.name || '') !== t('rackDiagram.defaultRackName')) return false
  if (Number(rack.totalHeight) !== 42) return false
  if (rack.createdAt || rack.created || rack.createDate || rack.location || rack.room || rack.position) return false
  const items = Array.isArray(rack.items) ? rack.items : []
  return items.length === 1 && items[0]?.type === 'gap' && Number(items[0]?.height) === 42
}

const normalizeLoadedRackLayouts = (layouts) => {
  if (!Array.isArray(layouts)) return []
  if (layouts.length === 1 && isLegacyAutoEmptyRack(layouts[0])) return []
  return layouts
}

const saveRackData = () => {
  if (!currentZoneId.value) {
    message.error(t('rackDiagram.msg.noZoneToSave'))
    return Promise.resolve(false)
  }
  if (selectedDevice.value && activeInlineField.value) {
    applyInlineDeviceChangesToRack()
  }

  if (!validateRackLayoutLimits(parsedRacks.value)) return Promise.resolve(false)

  saving.value = true
  const jsonContent = JSON.stringify(parsedRacks.value)

  return api('updateRackLayout', {}, 'POST', {
    zoneid: currentZoneId.value,
    name: 'default',
    content: jsonContent
  }).then(json => {
    message.success(t('rackDiagram.msg.rackSaved'))
    isDirty.value = false // 저장 성공 시 변경 상태 뱃지 숨김
    return true
  }).catch(error => {
    console.error('랙 데이터 저장 실패:', error)
    message.error(t('rackDiagram.msg.rackSaveFailed'))
    return false
  }).finally(() => {
    saving.value = false
  })
}

// ---------------- 랙 모달 로직 ----------------
const rackModalVisible = ref(false)
const rackModalMode = ref('add') // 'add' or 'edit'
const targetRackIndex = ref(-1)

const rackForm = reactive({ name: '', totalHeight: 42, location: '', createdAt: '' })
const rackFormErrors = reactive({ name: '', totalHeight: '' })

const clearRackFormErrors = () => {
  rackFormErrors.name = ''
  rackFormErrors.totalHeight = ''
}

const validateRackModalForm = () => {
  clearRackFormErrors()
  const rackName = String(rackForm.name || '').trim()

  if (!rackName) {
    rackFormErrors.name = t('rackDiagram.msg.enterRackName')
  } else if (rackName.length > 60) {
    rackFormErrors.name = t('rackDiagram.msg.rackNameMax')
  } else {
    const duplicateName = parsedRacks.value.some((rack, idx) => {
      if (rackModalMode.value === 'edit' && idx === targetRackIndex.value) return false
      return String(rack.name || '').trim().toLowerCase() === rackName.toLowerCase()
    })
    if (duplicateName) rackFormErrors.name = t('rackDiagram.msg.duplicateRackName')
  }

  if (!Number.isInteger(rackForm.totalHeight) || rackForm.totalHeight < 10 || rackForm.totalHeight > 50) {
    rackFormErrors.totalHeight = t('rackDiagram.msg.rackHeightRange')
  }

  return !rackFormErrors.name && !rackFormErrors.totalHeight
}

watch(() => rackForm.name, () => {
  if (rackFormErrors.name) rackFormErrors.name = ''
})

watch(() => rackForm.totalHeight, () => {
  if (rackFormErrors.totalHeight) rackFormErrors.totalHeight = ''
})

const openRackModal = (mode, index = -1) => {
  if (mode === 'add' && !validateRackCount([...parsedRacks.value, {}])) return

  clearRackFormErrors()
  rackModalMode.value = mode
  targetRackIndex.value = index
  if (mode === 'edit' && index > -1) {
    const rack = parsedRacks.value[index]
    rackForm.name = rack.name
    rackForm.totalHeight = rack.totalHeight
    rackForm.location = rack.location || rack.room || rack.position || ''
    rackForm.createdAt = rack.createdAt || rack.created || rack.createDate || ''
  } else {
    rackForm.name = ''
    rackForm.totalHeight = 42
    rackForm.location = ''
    rackForm.createdAt = new Date().toISOString()
  }
  rackModalVisible.value = true
}

const closeRackModal = () => {
  clearRackFormErrors()
  rackModalVisible.value = false
}

// 랙 전체를 이미지로 저장하는 함수
const exportToImage = async () => {
  const element = document.querySelector('.rack-container')
  if (!element) {
    message.warning(t('rackDiagram.msg.noRackToSaveImage'))
    return
  }

  try {
    message.loading({ content: t('rackDiagram.msg.exportingImage'), key: 'exporting' })
    const fullWidth = element.scrollWidth
    const fullHeight = element.scrollHeight

    // 가로 스크롤 영역 전체를 포함해 캡처
    const canvas = await html2canvas(element, {
      backgroundColor: '#eef0f4',
      scale: 2,
      width: fullWidth,
      height: fullHeight,
      windowWidth: fullWidth,
      windowHeight: fullHeight,
      scrollX: 0,
      scrollY: 0,
      onclone: (doc) => {
        const cloned = doc.querySelector('.rack-container')
        if (cloned) {
          cloned.style.overflow = 'visible'
          cloned.style.width = `${fullWidth}px`
          cloned.style.height = `${fullHeight}px`
        }
      }
    })

    const link = document.createElement('a')
    link.download = 'rack-diagram-export.png'
    link.href = canvas.toDataURL('image/png')
    link.click()

    message.success({ content: t('rackDiagram.msg.imageSaved'), key: 'exporting' })
  } catch (error) {
    console.error(error)
    message.error({ content: t('rackDiagram.msg.imageSaveFailed'), key: 'exporting' })
  }
}

// 파일 Input 요소를 참조하기 위한 ref
const fileInput = ref(null)

// JSON으로 내보내기 (Export)
const exportToJson = () => {
  if (!parsedRacks.value || parsedRacks.value.length === 0) {
    message.warning(t('rackDiagram.msg.noRackToExport'))
    return
  }

  // 1. 데이터를 JSON 문자열로 변환 (들여쓰기 2칸으로 보기 좋게)
  const dataStr = JSON.stringify(parsedRacks.value, null, 2)

  // 2. Blob 객체를 생성하여 파일화
  const blob = new Blob([dataStr], { type: 'application/json' })
  const url = URL.createObjectURL(blob)

  // 3. 가상의 a 태그를 만들어 클릭 이벤트 발생 (다운로드 실행)
  const link = document.createElement('a')
  link.href = url
  // 오늘 날짜를 파일명에 포함
  const dateStr = new Date().toISOString().slice(0, 10)
  link.download = `rack-data-${dateStr}.json`
  link.click()

  // 4. 메모리 정리
  URL.revokeObjectURL(url)
  message.success(t('rackDiagram.msg.jsonDownloaded'))
}

// JSON 불러오기 버튼 클릭 시 숨겨진 input 실행
const triggerImport = () => {
  if (fileInput.value) {
    fileInput.value.click()
  }
}

// 선택된 JSON 파일 읽고 화면에 렌더링 (Import)
const handleImport = (event) => {
  const file = event.target.files[0]
  if (!file) return

  const reader = new FileReader()
  reader.onload = (e) => {
    try {
      // 1. 파일 내용 파싱
      const importedData = JSON.parse(e.target.result)

      // 2. 간단한 유효성 검사 (배열 형태인지 확인)
      if (Array.isArray(importedData)) {
        if (!validateRackLayoutLimits(importedData)) return
        parsedRacks.value = importedData // 화면 갱신
        message.success(t('rackDiagram.msg.importSuccess'))
      } else {
        message.error(t('rackDiagram.msg.invalidFileFormat'))
      }
    } catch (error) {
      console.error(error)
      message.error(t('rackDiagram.msg.jsonParseFailed'))
    } finally {
      // 3. 같은 파일을 다시 선택할 수 있도록 input 초기화
      event.target.value = ''
    }
  }

  // 텍스트 형태로 파일 읽기 시작
  reader.readAsText(file)
}

const cloneRack = (rIndex) => {
  const targetRack = parsedRacks.value[rIndex]
  const newRack = {
    ...targetRack,
    name: `${targetRack.name} (${t('rackDiagram.copySuffix')})`,
    createdAt: new Date().toISOString(),
    created: undefined,
    createDate: undefined,
    items: targetRack.items.map(item => ({ ...item }))
  }
  const nextRacks = [...parsedRacks.value, newRack]
  if (!validateRackLayoutLimits(nextRacks)) return

  parsedRacks.value = nextRacks
  message.success(t('rackDiagram.msg.rackCloned'))
}

// 랙 모달 저장 로직
const submitRackModal = () => {
  if (!validateRackModalForm()) return

  const rackName = String(rackForm.name || '').trim()
  const rackLocation = String(rackForm.location || '').trim()

  if (rackModalMode.value === 'add') {
    const newRack = {
      name: rackName,
      totalHeight: rackForm.totalHeight,
      location: rackLocation,
      createdAt: rackForm.createdAt || new Date().toISOString(),
      items: [{ type: 'gap', height: rackForm.totalHeight }]
    }
    const nextRacks = [...parsedRacks.value, newRack]
    if (!validateRackLayoutLimits(nextRacks)) return
    parsedRacks.value = nextRacks
  } else {
    const targetRack = parsedRacks.value[targetRackIndex.value]
    const originalRack = JSON.parse(JSON.stringify(targetRack))
    const currentItemsHeight = targetRack.items.reduce((sum, item) => sum + item.height, 0)
    const diff = rackForm.totalHeight - currentItemsHeight

    if (diff > 0) {
      // 랙 크기가 커졌으면 맨 아래에 그만큼 여백(Gap) 추가
      targetRack.items.push({ type: 'gap', height: diff })
      targetRack.name = rackName
      targetRack.location = rackLocation
      targetRack.totalHeight = rackForm.totalHeight
    } else if (diff < 0) {
      // 랙 크기가 줄어들었을 때의 스마트 처리 로직
      let absDiff = Math.abs(diff)
      const tempItems = [...targetRack.items]

      // 1. 랙 안에 있는 '실제 장비(gap 제외)'들의 총 높이만 먼저 계산
      const equipmentHeight = tempItems
        .filter(item => item.type !== 'gap')
        .reduce((sum, item) => sum + item.height, 0)

      // 2. 실제 장비들의 총합보다 랙을 더 작게 만들 수는 없으므로 사전 차단
      if (rackForm.totalHeight < equipmentHeight) {
        message.error(`실제 장비가 차지하는 공간(${equipmentHeight}U) 이하로 랙 크기를 줄일 수 없습니다.`)
        return
      }

      // 3. 맨 아래부터 순회하며 빈 공간(gap)만 골라서 깎아냄
      for (let i = tempItems.length - 1; i >= 0; i--) {
        if (tempItems[i].type === 'gap') {
          if (tempItems[i].height > absDiff) {
            tempItems[i].height -= absDiff
            absDiff = 0
            break // 필요한 만큼 다 깎았으면 종료
          } else {
            absDiff -= tempItems[i].height
            tempItems.splice(i, 1) // 여백 크기가 작으면 완전 삭제하고 계속 진행
          }
        }
        // 장비(server, switch 등)를 만나면 아무 짓도 안 하고 그냥 건너뜀!

        // 목표치를 다 줄였으면 반복문 탈출
        if (absDiff === 0) break
      }

      targetRack.items = tempItems
      targetRack.name = rackName
      targetRack.location = rackLocation
      targetRack.totalHeight = rackForm.totalHeight
    } else {
      // 높이는 그대로고 이름만 변경된 경우
      targetRack.name = rackName
      targetRack.location = rackLocation
    }

    if (!validateRackLayoutLimits(parsedRacks.value)) {
      parsedRacks.value.splice(targetRackIndex.value, 1, originalRack)
      return
    }
  }
  closeRackModal()
}

const deleteRack = (index) => {
  parsedRacks.value.splice(index, 1)
  if (selectedRackIndex.value === index) {
    selectedRackIndex.value = -1
    showRackList.value = true
  } else if (selectedRackIndex.value > index) {
    selectedRackIndex.value -= 1
  }
  if (!parsedRacks.value.length) showRackList.value = true
}

const openRackDetail = (index) => {
  if (index < 0 || index >= parsedRacks.value.length) return
  selectedRackIndex.value = index
  selectedDeviceRackIndex.value = -1
  selectedDeviceItemIndex.value = -1
  showRackList.value = false
  isAutoZoomEnabled.value = true
  nextTick(() => applyResponsiveZoom(true))
}

const backToRackList = () => {
  selectedRackIndex.value = -1
  selectedDeviceRackIndex.value = -1
  selectedDeviceItemIndex.value = -1
  showRackList.value = true
}

const getRackNaturalWidth = () => {
  const rackWrapper = rackMainPaneRef.value?.querySelector('.rack-wrapper')
  if (!rackWrapper) return 0
  return Number.parseFloat(window.getComputedStyle(rackWrapper).width) || rackWrapper.offsetWidth || 0
}

const applyResponsiveZoom = (force = false, animate = false, availableWidth = 0) => {
  if (showRackList.value) return
  if (!force && !isAutoZoomEnabled.value) return

  const mainPaneWidth = availableWidth || rackMainPaneRef.value?.clientWidth || 0
  if (!mainPaneWidth) return

  let nextZoom
  if (selectedDevice.value) {
    const rackNaturalWidth = getRackNaturalWidth()
    if (!rackNaturalWidth) return
    const fitWidth = Math.max(0, mainPaneWidth - INSPECTOR_FIT_GUTTER)
    const fitRatio = fitWidth / rackNaturalWidth
    nextZoom = Math.max(INSPECTOR_ZOOM_MIN, Math.min(AUTO_ZOOM_MAX, Number(fitRatio.toFixed(3))))
  } else {
    // 장비 패널이 닫힌 상태에서는 기존 상세 화면의 여백 비율을 유지합니다.
    const baseWidth = 1280
    const ratio = mainPaneWidth / baseWidth
    nextZoom = Math.max(AUTO_ZOOM_MIN, Math.min(AUTO_ZOOM_MAX, Number(ratio.toFixed(2))))
  }

  if (animate) {
    animateZoomLevel(nextZoom)
  } else {
    zoomLevel.value = nextZoom
  }
}

const handleResponsiveResize = () => {
  if (resizeDebounceTimer) clearTimeout(resizeDebounceTimer)
  resizeDebounceTimer = setTimeout(() => {
    if (zoomRafId) cancelAnimationFrame(zoomRafId)
    zoomRafId = requestAnimationFrame(() => {
      applyResponsiveZoom(false, !!selectedDevice.value)
      scheduleSidePaneHeightUpdate()
    })
  }, 120)
}

const syncRackMainPaneResizeObserver = () => {
  if (rackMainPaneResizeObserver) {
    rackMainPaneResizeObserver.disconnect()
    rackMainPaneResizeObserver = null
  }
  if (showRackList.value || !rackMainPaneRef.value || typeof ResizeObserver === 'undefined') return

  rackMainPaneResizeObserver = new ResizeObserver(() => handleResponsiveResize())
  rackMainPaneResizeObserver.observe(rackMainPaneRef.value)
}

const selectDevice = (rIndex, iIndex) => {
  selectedDeviceRackIndex.value = rIndex
  selectedDeviceItemIndex.value = iIndex
}

const clearSelectedDevice = () => {
  selectedDeviceRackIndex.value = -1
  selectedDeviceItemIndex.value = -1
  activeInlineField.value = ''
  selectedDeviceDraftBaseline.value = ''
}

const openSelectedDeviceModal = () => {
  if (selectedDeviceRackIndex.value < 0 || selectedDeviceItemIndex.value < 0) return
  if (activeInlineField.value) finishInlineEdit()
  openDeviceModal(selectedDeviceRackIndex.value, selectedDeviceItemIndex.value)
}

const isSelectedItem = (rIndex, iIndex) => {
  return selectedDeviceRackIndex.value === rIndex && selectedDeviceItemIndex.value === iIndex
}

const isSelectedRulerUnit = (rack, rIndex, rulerIndex) => {
  if (selectedDeviceRackIndex.value !== rIndex || selectedDeviceItemIndex.value < 0) return false
  const item = rack?.items?.[selectedDeviceItemIndex.value]
  if (!item || item.type === 'gap') return false
  const itemTopIndex = rack.items
    .slice(0, selectedDeviceItemIndex.value)
    .reduce((sum, current) => sum + Number(current.height || 0), 0) + 1
  return rulerIndex >= itemTopIndex && rulerIndex < itemTopIndex + Number(item.height || 0)
}

const getRackUsedU = (rack) => {
  if (!rack?.items?.length) return 0
  return rack.items
    .filter(item => item.type !== 'gap')
    .reduce((sum, item) => sum + Number(item.height || 0), 0)
}
const getRackFreeU = (rack) => Math.max(0, Number(rack?.totalHeight || 0) - getRackUsedU(rack))
const getRackDeviceCount = (rack) => (rack?.items || []).filter(item => item.type !== 'gap').length
const getRackUsagePercent = (rack) => {
  const total = Number(rack?.totalHeight || 0)
  if (!total) return 0
  return Math.round((getRackUsedU(rack) / total) * 100)
}
const rackAggregate = computed(() => {
  const racks = parsedRacks.value || []
  const totalRacks = racks.length
  const totalDevices = racks.reduce((sum, rack) => sum + getRackDeviceCount(rack), 0)
  const totalUsedU = racks.reduce((sum, rack) => sum + getRackUsedU(rack), 0)
  const totalHeight = racks.reduce((sum, rack) => sum + Number(rack?.totalHeight || 0), 0)
  const totalFreeU = Math.max(0, totalHeight - totalUsedU)
  const averageUsage = totalHeight ? Math.round((totalUsedU / totalHeight) * 100) : 0
  return {
    totalRacks,
    totalDevices,
    totalUsedU,
    totalHeight,
    totalFreeU,
    averageUsage
  }
})
const formatRackDate = (value) => {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  const yyyy = date.getFullYear()
  const mm = String(date.getMonth() + 1).padStart(2, '0')
  const dd = String(date.getDate()).padStart(2, '0')
  return `${yyyy}.${mm}.${dd}`
}
const getRackCreatedDate = (rack) => formatRackDate(rack?.createdAt || rack?.created || rack?.createDate)
const getRackLocation = (rack) => rack?.location || rack?.room || rack?.position || '-'
const overflowTitleState = reactive({})
const overflowTitleRefs = new Map()

const setOverflowTitleRef = (key) => (el) => {
  if (el) overflowTitleRefs.set(key, el)
  else overflowTitleRefs.delete(key)
}

const updateOverflowTitle = (key) => {
  const el = overflowTitleRefs.get(key)
  overflowTitleState[key] = !!el && (el.scrollWidth > el.clientWidth + 1)
}

const getOverflowTitle = (key, fullText) => (overflowTitleState[key] ? fullText : null)

const searchQuery = ref('')
const hasSearchQuery = computed(() => !!searchQuery.value?.trim())

const getRackMatchCount = (rack) => {
  const query = searchQuery.value?.trim().toLowerCase()
  if (!query) return 0

  let count = 0
  if ((rack?.name || '').toLowerCase().includes(query)) count += 1

  for (const item of (rack?.items || [])) {
    if (item?.type === 'gap') continue
    const target = `${item?.label || ''} ${item?.memo || ''} ${item?.sourceRef || ''}`.toLowerCase()
    if (target.includes(query)) count += 1
  }
  return count
}

const isRackMatched = (rack) => {
  const query = searchQuery.value?.trim().toLowerCase()
  if (!query) return true
  if ((rack?.name || '').toLowerCase().includes(query)) return true

  return (rack?.items || []).some((item) => {
    if (item?.type === 'gap') return false
    const target = `${item?.label || ''} ${item?.memo || ''} ${item?.sourceRef || ''}`.toLowerCase()
    return target.includes(query)
  })
}

const isMatched = (item) => {
  const query = searchQuery.value?.trim().toLowerCase()
  if (!query) return true
  if (item?.type === 'gap') return true
  const target = `${item?.label || ''} ${item?.memo || ''} ${item?.sourceRef || ''}`.toLowerCase()
  return target.includes(query)
}

// ---------------- 장비 모달/관리 로직 ----------------
const deviceModalVisible = ref(false)
const targetItemIndex = ref(-1)

const deviceForm = reactive({
  type: 'server',
  label: '',
  height: 1,
  customType: '',
  memo: '',
  sourceRef: undefined,
  hardwareInfo: createEmptyHardwareInfo()
})
const deviceFormErrors = reactive({ type: '', label: '', customType: '', height: '' })

const clearDeviceFormErrors = () => {
  deviceFormErrors.type = ''
  deviceFormErrors.label = ''
  deviceFormErrors.customType = ''
  deviceFormErrors.height = ''
}

const validateDeviceModalForm = () => {
  clearDeviceFormErrors()
  const rawLabel = String(deviceForm.label || '').trim()
  const rawCustomType = String(deviceForm.customType || '').trim()

  if (!deviceForm.type) {
    deviceFormErrors.type = t('rackDiagram.msg.selectDeviceType')
  }
  if (deviceForm.type !== 'blank') {
    if (!rawLabel) deviceFormErrors.label = t('rackDiagram.msg.enterDeviceName')
    else if (rawLabel.length > 60) deviceFormErrors.label = t('rackDiagram.msg.deviceNameMax')
  }
  if (deviceForm.type === 'custom') {
    if (!rawCustomType) deviceFormErrors.customType = t('rackDiagram.msg.enterCustomType')
    else if (rawCustomType.length > 60) deviceFormErrors.customType = t('rackDiagram.msg.customTypeMax')
  }
  if (!Number.isInteger(deviceForm.height) || deviceForm.height <= 0) {
    deviceFormErrors.height = t('rackDiagram.msg.deviceHeightInteger')
  } else if (deviceForm.height > maxAllowedHeight.value) {
    deviceFormErrors.height = t('rackDiagram.msg.deviceLargerThanGap')
  }

  return !deviceFormErrors.type && !deviceFormErrors.label && !deviceFormErrors.customType && !deviceFormErrors.height
}

watch(() => deviceForm.type, () => {
  if (deviceFormErrors.type) deviceFormErrors.type = ''
})

watch(() => deviceForm.label, () => {
  if (deviceFormErrors.label) deviceFormErrors.label = ''
})

watch(() => deviceForm.customType, () => {
  if (deviceFormErrors.customType) deviceFormErrors.customType = ''
})

watch(() => deviceForm.height, () => {
  if (deviceFormErrors.height) deviceFormErrors.height = ''
})

const inventoryLoading = ref(false)
const inventoryOptions = ref([])
const hostCache = ref({})
const hostVmModalVisible = ref(false)
const hostVmModalTitle = ref(t('rackDiagram.hostVmList'))
const hostVmLoading = ref(false)
const hostVmList = ref([])
const hostVmFallbackList = ref([])
const vmOsTypeCache = new Map()
const dragSource = ref({ rIndex: -1, iIndex: -1 })
const quickLinksError = ref('')
const quickLinkRows = ref([])
const deviceSpecColumns = computed(() => ([
  { title: t('rackDiagram.memoKey'), key: 'key', dataIndex: 'key', width: '40%' },
  { title: t('rackDiagram.memoValue'), key: 'value', dataIndex: 'value' },
  { title: t('rackDiagram.quickLinkAction'), key: 'action', dataIndex: 'action', width: 56 }
]))
const quickLinkColumns = computed(() => ([
  { title: t('rackDiagram.quickLinkName'), key: 'label', dataIndex: 'label', width: '35%' },
  { title: t('rackDiagram.quickLinkUrl'), key: 'url', dataIndex: 'url' },
  { title: t('rackDiagram.quickLinkAction'), key: 'action', dataIndex: 'action', width: 56 }
]))

const addDeviceSpecRow = () => {
  deviceSpecRows.value.push(makeMemoRow())
}

const removeDeviceSpecRow = (index) => {
  deviceSpecRows.value.splice(index, 1)
  if (!deviceSpecRows.value.length) deviceSpecRows.value.push(makeMemoRow())
}

const getCleanSpecRows = (rows) => {
  return rows
    .map(row => ({ key: String(row.key || '').trim(), value: formatDeviceSpecValue(row) }))
    .filter(row => row.key || row.value)
}

const makeQuickLinkRow = (label = '', url = '') => ({
  key: `${Date.now()}-${Math.random().toString(16).slice(2)}`,
  label,
  url
})

const addQuickLinkRow = () => {
  quickLinkRows.value.push(makeQuickLinkRow())
}

const removeQuickLinkRow = (index) => {
  quickLinkRows.value.splice(index, 1)
  if (!quickLinkRows.value.length) quickLinkRows.value.push(makeQuickLinkRow())
}
// 샘플(테스트) VM(가상머신) 목록
const ENABLE_VM_FALLBACK_MOCK = false
const HOST_ACTIVE_VM_STATES = new Set(['running', 'starting', 'stopping', 'migrating'])

const buildVmMockList = (hostId, hostName = 'sample') => {
  const osPool = [
    { id: '8b8c0681-8ed7-11f1-b7da-00248162d5a3', name: 'Windows Server 2022 (64-bit)' },
    { id: '7cca2285-8ed7-11f1-b7da-00248162d5a3', name: 'Rocky Linux 9' },
    { id: '1f54dd03-800f-4403-b003-810b16debd42', name: 'Ubuntu 22.04 LTS' },
    { id: '7cc9bfb8-8ed7-11f1-b7da-00248162d5a3', name: 'CentOS 9' },
    { id: '0fc630a1-9bb6-4eb0-b91b-5e929d73f3c7', name: 'Debian GNU/Linux 12 (64-bit)' },
    { id: '5725f729-8ed7-11f1-b7da-00248162d5a3', name: 'SUSE Linux Enterprise Server 15 (64-bit)' },
    { id: '9a61346d-39f6-4e64-b142-1e9b5de9a276', name: 'Red Hat Enterprise Linux 9.0' },
    { id: 'e1cb87b9-94f3-4d2c-a0da-85ceb188373a', name: 'Fedora Linux (64 bit)' },
    { id: '7cc997b3-8ed7-11f1-b7da-00248162d5a3', name: 'AlmaLinux 9' },
    { id: 'df5c8d1d-8ed6-11f1-b7da-00248162d5a3', name: 'Other Linux (64-bit)' }
  ]
  const statePool = ['Running', 'Starting', 'Migrating', 'Stopping']
  const list = []
  for (let i = 1; i <= 20; i++) {
    const idx = i - 1
    const no = String(i).padStart(2, '0')
    const os = osPool[idx % osPool.length]
    list.push({
      id: `sample-${hostId}-${no}`,
      name: `${hostName}-vm-${no}`,
      displayname: `${hostName}-vm-${no}`,
      state: statePool[idx % statePool.length],
      hostid: hostId,
      hostname: hostName,
      hypervisor: 'KVM',
      guestosid: os.id,
      ostypeid: os.id,
      osdisplayname: os.name,
      ipaddress: `10.10.254.${100 + i}`,
      nic: [{
        id: `sample-nic-${hostId}-${no}`,
        ipaddress: `10.10.254.${100 + i}`,
        isdefault: true,
        traffictype: 'Guest'
      }]
    })
  }
  return list
}

const filteredHostVmList = computed(() => {
  const merged = hostVmList.value.length ? hostVmList.value : hostVmFallbackList.value
  return merged.filter(vm => HOST_ACTIVE_VM_STATES.has(String(vm?.state || '').toLowerCase()))
})

const buildInventoryOptions = async () => {
  if (!currentZoneId.value) return
  inventoryLoading.value = true
  const options = []

  try {
    const hostJson = await api('listHosts', { zoneid: currentZoneId.value, listall: true })
    const hosts = hostJson?.listhostsresponse?.host || []
    hosts.forEach(h => {
      const name = h.name || h.hostname || h.id
      const ip = h.ipaddress ? ` / ${h.ipaddress}` : ''
      options.push({
        label: `[Host] ${name}${ip}`,
        value: `host:${h.id}`,
        meta: { kind: 'host', id: h.id, name, ip: h.ipaddress || '' }
      })
      hostCache.value[h.id] = h
    })
  } catch (e) {
    console.warn('listHosts failed:', e)
  }

  inventoryOptions.value = options
  inventoryLoading.value = false
}

const handleSourceChange = (value) => {
  if (!value) return
  const selected = inventoryOptions.value.find(o => o.value === value)
  if (!selected) return

  deviceForm.label = selected.meta?.name || selected.label || deviceForm.label
}

const getLinkedHostId = (item) => {
  if (!item?.sourceRef || typeof item.sourceRef !== 'string') return null
  if (!item.sourceRef.startsWith('host:')) return null
  return item.sourceRef.split(':')[1] || null
}

const isHostLinked = (item) => {
  return !!getLinkedHostId(item)
}

const parseQuickLinksRowsWithValidation = (rows) => {
  if (!rows || !rows.length) return { links: [], errors: [] }
  const links = []
  const errors = []

  rows.forEach((row, idx) => {
    const label = String(row?.label || '').trim()
    const rawUrl = String(row?.url || '').trim()
    if (!label && !rawUrl) return

    if (!rawUrl) {
      errors.push(t('rackDiagram.msg.quickLinkUrlEmpty', { line: idx + 1 }))
      return
    }

    // 스키마를 생략한 입력도 허용 (예: 10.10.12.2:9090)
    const normalizedUrl = /^https?:\/\//i.test(rawUrl) ? rawUrl : `https://${rawUrl}`
    let parsed = null
    try {
      parsed = new URL(normalizedUrl)
    } catch (e) {
      parsed = null
    }
    if (!parsed || !/^https?:$/i.test(parsed.protocol)) {
      errors.push(t('rackDiagram.msg.quickLinkUrlInvalid', { line: idx + 1 }))
      return
    }

    links.push({ label, url: normalizedUrl })
  })

  return { links, errors }
}

const getQuickLinks = (item) => {
  if (!item?.quickLinks || !Array.isArray(item.quickLinks)) return []
  return item.quickLinks.filter(link => link?.url)
}

const hasQuickLinks = (item) => getQuickLinks(item).length > 0
const hasActionMenu = (item) => isHostLinked(item) || hasQuickLinks(item)

const compactGaps = (rack) => {
  for (let i = rack.items.length - 1; i > 0; i--) {
    if (rack.items[i].type === 'gap' && rack.items[i - 1].type === 'gap') {
      rack.items[i - 1].height += rack.items[i].height
      rack.items.splice(i, 1)
    }
  }
}

const onDragStart = (rIndex, iIndex) => {
  dragSource.value = { rIndex, iIndex }
}

const getDesiredStartUFromRackEvent = (event, rack) => {
  if (!event?.currentTarget) return 0
  const rect = event.currentTarget.getBoundingClientRect()
  const y = Math.max(0, Math.min(rect.height - 1, event.clientY - rect.top))
  const rackHeight = rack?.totalHeight || rack.items.reduce((s, i) => s + i.height, 0)
  const unitHeight = rect.height / rackHeight
  const raw = Math.floor(y / unitHeight)
  return Math.max(0, Math.min(rackHeight - 1, raw))
}

const findBestStartUInRack = (rack, deviceHeight, desiredStartU) => {
  compactGaps(rack)
  let cursor = 0
  let best = null
  for (let i = 0; i < rack.items.length; i++) {
    const item = rack.items[i]
    if (item.type === 'gap') {
      const minStart = cursor
      const maxStart = cursor + item.height - deviceHeight
      if (maxStart >= minStart) {
        const candidate = Math.max(minStart, Math.min(desiredStartU, maxStart))
        const dist = Math.abs(candidate - desiredStartU)
        if (!best || dist < best.dist) {
          best = { index: i, startU: candidate, dist }
        }
      }
    }
    cursor += item.height
  }
  return best
}

const placeDeviceAtStartU = (rack, gapIndex, startU, device) => {
  let cursor = 0
  for (let i = 0; i < gapIndex; i++) cursor += rack.items[i].height
  const gap = rack.items[gapIndex]
  if (!gap || gap.type !== 'gap') return false

  const localStart = startU - cursor
  const localEnd = localStart + device.height
  if (localStart < 0 || localEnd > gap.height) return false

  const before = localStart
  const after = gap.height - localEnd
  const insert = []
  if (before > 0) insert.push({ type: 'gap', height: before })
  insert.push({ ...device })
  if (after > 0) insert.push({ type: 'gap', height: after })
  rack.items.splice(gapIndex, 1, ...insert)
  compactGaps(rack)
  return true
}

const buildInlineDeviceItem = () => {
  if (!selectedDevice.value) return null
  const rawLabel = String(selectedDeviceDraft.label || '').trim()
  const finalLabel = rawLabel || getDefaultLabel(selectedDevice.value.type) || t('rackDiagram.newDevice')
  const height = Number(selectedDeviceDraft.height || 0)

  if (selectedDevice.value.type !== 'blank' && !finalLabel) {
    message.warning(t('rackDiagram.msg.enterDeviceName'))
    return null
  }
  if (finalLabel.length > 60) {
    message.warning(t('rackDiagram.msg.deviceNameMax'))
    return null
  }
  if (!Number.isInteger(height) || height <= 0) {
    message.warning(t('rackDiagram.msg.deviceHeightInteger'))
    return null
  }

  const cleanHardwareInfo = validateHardwareInfo(selectedDeviceDraft.hardwareInfo)
  if (!cleanHardwareInfo) return null

  const quickLinkParsed = parseQuickLinksRowsWithValidation(selectedDeviceDraft.quickLinks)
  if (quickLinkParsed.errors.length > 0) {
    message.warning(quickLinkParsed.errors[0])
    activeInlineField.value = 'quickLinks'
    return null
  }

  return {
    ...selectedDevice.value,
    label: finalLabel,
    height,
    customType: String(selectedDeviceDraft.customType || '').trim(),
    memo: stripLinkedAssetMemo(selectedDeviceDraft.memo),
    hardwareInfo: cleanHardwareInfo,
    specs: getCleanSpecRows(selectedDeviceDraft.specs),
    sourceRef: selectedDevice.value.type === 'server' ? (selectedDeviceDraft.sourceRef || null) : null,
    quickLinks: quickLinkParsed.links
  }
}

const applyInlineDeviceChangesToRack = () => {
  if (!selectedDevice.value || selectedDeviceRackIndex.value < 0 || selectedDeviceItemIndex.value < 0) return
  const rack = parsedRacks.value[selectedDeviceRackIndex.value]
  const oldItem = rack?.items?.[selectedDeviceItemIndex.value]
  if (!rack || !oldItem || oldItem.type === 'gap') return

  const newItem = buildInlineDeviceItem()
  if (!newItem) return

  const desiredBottomU = Number(selectedDeviceDraft.startU || 1)
  const desiredStartIndex = Number(rack.totalHeight || 0) - (desiredBottomU + Number(newItem.height || 0) - 1)
  if (!Number.isInteger(desiredStartIndex) || desiredStartIndex < 0) {
    message.warning(t('rackDiagram.msg.notEnoughGapBelow'))
    return
  }

  const backupItems = JSON.stringify(rack.items)
  const token = `${Date.now()}-${Math.random().toString(36).slice(2)}`
  const deviceToPlace = { ...newItem, __inlineToken: token }

  rack.items.splice(selectedDeviceItemIndex.value, 1, { type: 'gap', height: Number(oldItem.height || 0) })
  compactGaps(rack)

  const best = findBestStartUInRack(rack, Number(deviceToPlace.height || 0), desiredStartIndex)
  if (!best || best.startU !== desiredStartIndex) {
    rack.items = JSON.parse(backupItems)
    message.warning(t('rackDiagram.msg.notEnoughGapBelow'))
    return
  }

  const placed = placeDeviceAtStartU(rack, best.index, best.startU, deviceToPlace)
  if (!placed) {
    rack.items = JSON.parse(backupItems)
    message.warning(t('rackDiagram.msg.dropCalcFailed'))
    return
  }

  const newIndex = rack.items.findIndex(item => item.__inlineToken === token)
  if (newIndex > -1) {
    delete rack.items[newIndex].__inlineToken
    selectedDeviceItemIndex.value = newIndex
  }
  syncSelectedDeviceDraft(rack.items[selectedDeviceItemIndex.value], false)
}

const onDropRackFrame = (targetRIndex, event) => {
  const { rIndex: sourceRIndex, iIndex: sourceIIndex } = dragSource.value
  dragSource.value = { rIndex: -1, iIndex: -1 }

  if (sourceRIndex < 0 || sourceIIndex < 0) return
  const sourceRack = parsedRacks.value[sourceRIndex]
  const targetRack = parsedRacks.value[targetRIndex]
  const sourceItem = sourceRack?.items?.[sourceIIndex]
  if (!sourceRack || !targetRack || !sourceItem || sourceItem.type === 'gap') return

  const moving = { ...sourceItem }
  const sameRack = sourceRIndex === targetRIndex

  // 원본 위치 비우기
  sourceRack.items.splice(sourceIIndex, 1, { type: 'gap', height: moving.height })
  compactGaps(sourceRack)

  const desiredStartU = getDesiredStartUFromRackEvent(event, targetRack)
  const best = findBestStartUInRack(targetRack, moving.height, desiredStartU)
  if (!best) {
    message.warning(t('rackDiagram.msg.notEnoughGapOnTarget', { height: moving.height }))
    // 복구
    const restore = findBestStartUInRack(sourceRack, moving.height, 0)
    if (restore) placeDeviceAtStartU(sourceRack, restore.index, restore.startU, moving)
    return
  }

  const placed = placeDeviceAtStartU(targetRack, best.index, best.startU, moving)
  if (!placed) {
    message.warning(t('rackDiagram.msg.dropCalcFailed'))
    const restore = findBestStartUInRack(sourceRack, moving.height, 0)
    if (restore) placeDeviceAtStartU(sourceRack, restore.index, restore.startU, moving)
    return
  }

  if (sameRack) compactGaps(targetRack)
}

const fetchHostById = async (hostId) => {
  if (!hostId) return null
  if (hostCache.value[hostId]) return hostCache.value[hostId]
  const json = await api('listHosts', { id: hostId })
  const host = json?.listhostsresponse?.host?.[0] || null
  if (host) hostCache.value[hostId] = host
  return host
}

const goToLinkedHost = (item) => {
  const hostId = getLinkedHostId(item)
  if (!hostId) return
  router.push({ path: `/host/${hostId}` })
}

const openLinkedHostOobm = async (item) => {
  const hostId = getLinkedHostId(item)
  if (!hostId) return
  try {
    const host = await fetchHostById(hostId)
    const protocol = host?.details?.manageconsoleprotocol || 'http'
    const address = host?.outofbandmanagement?.address || ''
    const port = host?.details?.manageconsoleport
    if (!address || !port) {
      message.warning(t('rackDiagram.msg.oobmNotFound'))
      return
    }
    window.open(`${protocol}://${address}:${port}`, '_blank')
  } catch (e) {
    message.error(t('rackDiagram.msg.oobmLoadFailed'))
  }
}

const openLinkedHostCube = async (item) => {
  const hostId = getLinkedHostId(item)
  if (!hostId) return
  try {
    const host = await fetchHostById(hostId)
    const ip = host?.ipaddress
    if (!ip) {
      message.warning(t('rackDiagram.msg.hostIpNotFound'))
      return
    }
    window.open(`https://${ip}:9090`, '_blank')
  } catch (e) {
    message.error(t('rackDiagram.msg.cubeLoadFailed'))
  }
}

const openLinkedHostVmModal = async (item) => {
  const hostId = getLinkedHostId(item)
  if (!hostId) return
  hostVmLoading.value = true
  hostVmModalVisible.value = true
  hostVmList.value = []
  hostVmFallbackList.value = []

  try {
    const host = await fetchHostById(hostId)
    hostVmModalTitle.value = `${t('rackDiagram.hostVmList')} - ${host?.name || host?.hostname || hostId}`
    const hostIdStr = String(hostId)
    const isActiveHostVm = (vm) => {
      const vmState = String(vm?.state || '').toLowerCase()
      return HOST_ACTIVE_VM_STATES.has(vmState)
    }
    const isVmAssignedToHost = (vm) => {
      const vmHostId = String(vm?.hostid || '')
      // 정책: 현재 host_id가 해당 호스트 + 활성 상태 VM만 표시
      return vmHostId === hostIdStr && isActiveHostVm(vm)
    }
    const readVmList = (json) => json?.listvirtualmachinesresponse?.virtualmachine || []
    const hostVmListParams = {
      hostid: hostId,
      listall: true,
      details: 'group,nics,secgrp,tmpl,servoff,diskoff,iso,volume,affgrp,backoff',
      isvnf: false,
      page: 1,
      pagesize: 500,
      showIcon: true
    }

    // 호스트 상세 메뉴의 VM 목록 호출과 동일한 파라미터를 사용한다.
    const directJson = await api('listVirtualMachines', hostVmListParams)
    let vms = readVmList(directJson).filter(isActiveHostVm)

    // 2) hostid 조회가 비면 전체에서 host 매핑 기준으로 재탐색
    if (!vms.length) {
      const allJson = await api('listVirtualMachines', {
        listall: true,
        details: hostVmListParams.details,
        isvnf: false,
        page: 1,
        pagesize: 500
      })
      const allVms = readVmList(allJson)
      vms = allVms.filter(isVmAssignedToHost)
    }

    hostVmList.value = await resolveVmOsTypeNames(vms)

    // 개발환경 fallback: host 매핑 샘플 제공
    if (ENABLE_VM_FALLBACK_MOCK && !hostVmList.value.length) {
      const hostName = host?.name || host?.hostname || hostId
      hostVmFallbackList.value = buildVmMockList(hostId, hostName)
    }
  } catch (e) {
    // 통일된 UI 정책: 실패 시 토스트 없이 No Data 표시
    hostVmList.value = []
    hostVmFallbackList.value = ENABLE_VM_FALLBACK_MOCK
      ? buildVmMockList(hostId, 'sample')
      : []
  } finally {
    hostVmLoading.value = false
  }
}

const getVmOsTypeId = (vm) => String(vm?.ostypeid || vm?.guestosid || '')

const getVmOsTypeInfo = (vm) => {
  const cached = vmOsTypeCache.get(getVmOsTypeId(vm)) || {}
  const displayName = String(
    vm?.ostypename ||
    vm?.osdisplayname ||
    vm?.guestosname ||
    cached.description ||
    cached.name ||
    ''
  ).trim()
  const categoryName = String(vm?.oscategoryname || cached.categoryName || '').trim()

  return {
    displayName,
    searchText: [displayName, cached.name, cached.description, categoryName]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
  }
}

const resolveVmOsTypeNames = async (vms) => {
  const unresolvedIds = [...new Set(vms
    .filter(vm => !getVmOsTypeInfo(vm).displayName)
    .map(getVmOsTypeId)
    .filter(id => id && !vmOsTypeCache.has(id)))]

  if (unresolvedIds.length) {
    try {
      const json = await api('listOsTypes', { listall: true })
      const osTypes = json?.listostypesresponse?.ostype || []
      osTypes.forEach(osType => {
        if (osType?.id) {
          vmOsTypeCache.set(String(osType.id), {
            name: String(osType.name || ''),
            description: String(osType.description || ''),
            categoryName: String(osType.oscategoryname || '')
          })
        }
      })
    } catch (e) {
      console.warn('listOsTypes failed:', e)
    }
  }

  return vms
}

const getVmOsLogo = (vm) => {
  const osname = getVmOsTypeInfo(vm).searchText
  // Font Awesome에는 Rocky/Alma/Amazon/Oracle Linux 전용 브랜드가 없어 Linux로 통일한다.
  if (
    osname.includes('rocky') ||
    osname.includes('alma') ||
    osname.includes('amazon linux') ||
    osname.includes('oracle linux')
  ) return 'linux'
  if (osname.includes('centos')) return 'centos'
  if (osname.includes('debian')) return 'debian'
  if (osname.includes('ubuntu')) return 'ubuntu'
  if (osname.includes('suse') || osname.includes('opensuse')) return 'suse'
  if (osname.includes('redhat') || osname.includes('red hat') || osname.includes('rhel')) return 'redhat'
  if (osname.includes('fedora')) return 'fedora'
  if (osname.includes('windows') || osname.includes('microsoft') || osname.includes('dos')) return 'windows'
  if (osname.includes('linux')) return 'linux'
  if (osname.includes('bsd')) return 'freebsd'
  if (osname.includes('apple') || osname.includes('mac')) return 'apple'
  return 'linux'
}

const isRunningVm = (vm) => {
  return String(vm?.state || '').toLowerCase() === 'running'
}

const collectVmIpAddresses = (vm) => {
  const nics = Array.isArray(vm?.nic) ? vm.nic : []
  const ips = []
  const pushIp = (ip) => {
    const value = String(ip || '').trim()
    if (value && !ips.includes(value)) ips.push(value)
  }

  const defaultNic = nics.find(nic => nic?.isdefault === true || String(nic?.isdefault).toLowerCase() === 'true')
  pushIp(defaultNic?.ipaddress)

  const guestNic = nics.find(nic => String(nic?.type || '').toLowerCase() === 'guest' && nic?.ipaddress)
  pushIp(guestNic?.ipaddress)

  nics.forEach(nic => {
    pushIp(nic?.ipaddress)
    const secondaryIps = Array.isArray(nic?.secondaryip) ? nic.secondaryip : []
    secondaryIps.forEach(ip => pushIp(ip?.ipaddress || ip?.ip))
  })

  return ips
}

const getVmPrimaryIpText = (vm) => {
  const ips = collectVmIpAddresses(vm)
  if (!ips.length) return '-'
  const moreCount = ips.length - 1
  return moreCount > 0 ? `${ips[0]} ${t('rackDiagram.moreIpCount', { count: moreCount })}` : ips[0]
}

const goToVmDetail = (vm) => {
  const vmId = vm?.id
  if (!vmId) return
  hostVmModalVisible.value = false
  router.push({ path: `/vm/${vmId}` })
}

const handleHostActionMenu = (key, item) => {
  if (typeof key === 'string' && key.startsWith('quick-')) {
    const idx = Number(key.replace('quick-', ''))
    const link = getQuickLinks(item)[idx]
    if (link?.url) window.open(link.url, '_blank')
    return
  }
  if (key === 'host-detail') goToLinkedHost(item)
  if (key === 'host-vms') openLinkedHostVmModal(item)
  if (key === 'host-oobm') openLinkedHostOobm(item)
  if (key === 'host-cube') openLinkedHostCube(item)
}

// 입력 가능한 최대 높이 사전 계산 (물리적 한계)
const maxAllowedHeight = computed(() => {
  if (targetRackIndex.value === -1 || targetItemIndex.value === -1) return 42
  const rack = parsedRacks.value[targetRackIndex.value]
  const item = rack.items[targetItemIndex.value]

  let physicalMax = item.height // 기본적으로 내 크기 보장

  if (item.type === 'gap') {
    physicalMax = item.height // 빈 공간이면 그 크기가 한계
  } else {
    // 기존 장비 수정 시: 바로 아래가 여백이면 그 공간까지 끌어다 쓸 수 있음
    if (targetItemIndex.value < rack.items.length - 1) {
      const nextItem = rack.items[targetItemIndex.value + 1]
      if (nextItem.type === 'gap') physicalMax += nextItem.height
    }
  }
  return physicalMax
})

// 각 장비별 기본 라벨명 매핑
const defaultLabelKeys = {
  server: 'rackDiagram.defaultLabelServer',
  blade: 'rackDiagram.defaultLabelBlade',
  switch: 'rackDiagram.defaultLabelSwitch',
  router: 'rackDiagram.defaultLabelRouter',
  loadbalancer: 'rackDiagram.defaultLabelLoadBalancer',
  storage: 'rackDiagram.defaultLabelStorage',
  nas: 'rackDiagram.defaultLabelNas',
  firewall: 'rackDiagram.defaultLabelFirewall',
  monitoring: 'rackDiagram.defaultLabelMonitoring',
  kvm: 'rackDiagram.defaultLabelKvm',
  cooling: 'rackDiagram.defaultLabelCooling',
  patch: 'rackDiagram.defaultLabelPatch',
  pdu: 'rackDiagram.defaultLabelPdu',
  ups: 'rackDiagram.defaultLabelUps',
  blank: 'rackDiagram.defaultLabelBlank',
  custom: 'rackDiagram.defaultLabelCustom'
}
const getDefaultLabel = (type) => t(defaultLabelKeys[type] || 'rackDiagram.defaultLabelCustom')

// 모달 열기 로직 수정
const openDeviceModal = (rIndex, iIndex) => {
  clearDeviceFormErrors()
  targetRackIndex.value = rIndex
  targetItemIndex.value = iIndex
  const item = parsedRacks.value[rIndex].items[iIndex]

  if (item.type === 'gap') {
    deviceForm.type = 'server'
    deviceForm.label = ''
    deviceForm.height = Math.min(2, maxAllowedHeight.value)
    deviceForm.customType = ''
    deviceForm.memo = ''
    deviceForm.sourceRef = undefined
    assignHardwareInfo(deviceForm.hardwareInfo, null)
    deviceSpecRows.value = [makeMemoRow()]
    quickLinkRows.value = [makeQuickLinkRow()]
    quickLinksError.value = ''
  } else {
    // 수정 모드일 때는 기존 데이터 로드
    deviceForm.type = item.type
    deviceForm.label = item.label || ''
    deviceForm.height = item.height
    deviceForm.customType = item.customType || ''
    deviceForm.memo = getDeviceFreeMemo(item)
    deviceForm.sourceRef = item.sourceRef || undefined
    assignHardwareInfo(deviceForm.hardwareInfo, item.hardwareInfo)
    deviceSpecRows.value = getDeviceSpecRows(item).length ? getDeviceSpecRows(item) : [makeMemoRow()]
    quickLinkRows.value = getQuickLinks(item).length
      ? getQuickLinks(item).map(link => makeQuickLinkRow(link.label || '', link.url || ''))
      : [makeQuickLinkRow()]
    quickLinksError.value = ''
  }
  if (!inventoryOptions.value.length) {
    buildInventoryOptions()
  }
  deviceModalVisible.value = true
}

// 장비 종류 Select Box 변경 시 라벨 자동 업데이트
const handleTypeChange = (newType) => {
  clearDeviceFormErrors()
  // 사용자가 직접 입력한 라벨이 없거나, 기존 기본 라벨명과 똑같을 때만 새 기본명으로 교체
  const isLabelEmpty = !deviceForm.label
  const isLabelDefault = Object.values(defaultLabelKeys).map(key => t(key)).includes(deviceForm.label)

  if (isLabelEmpty || isLabelDefault) {
    deviceForm.label = getDefaultLabel(newType) || ''
  }
  if (newType === 'server') {
    deviceForm.height = Math.min(2, maxAllowedHeight.value)
  }
  if (newType !== 'server') {
    deviceForm.sourceRef = undefined
  }
}

const closeDeviceModal = () => {
  clearDeviceFormErrors()
  deviceModalVisible.value = false
}

const submitDeviceModal = () => {
  if (!validateDeviceModalForm()) return

  const rIndex = targetRackIndex.value
  const iIndex = targetItemIndex.value
  const rack = parsedRacks.value[rIndex]
  const oldItem = rack.items[iIndex]

  const rawLabel = String(deviceForm.label || '').trim()
  const rawCustomType = String(deviceForm.customType || '').trim()
  const finalLabel = deviceForm.type === 'blank' ? (rawLabel || getDefaultLabel('blank')) : rawLabel
  const finalCustomType = rawCustomType || (deviceForm.type === 'custom' ? t('rackDiagram.customUnit') : '')
  const cleanHardwareInfo = validateHardwareInfo(deviceForm.hardwareInfo)
  if (!cleanHardwareInfo) return
  const quickLinkParsed = parseQuickLinksRowsWithValidation(quickLinkRows.value)
  if (quickLinkParsed.errors.length > 0) {
    quickLinksError.value = quickLinkParsed.errors[0]
    return
  }
  quickLinksError.value = ''
  const cleanSpecs = getCleanSpecRows(deviceSpecRows.value)

  const newItem = {
    type: deviceForm.type,
    label: finalLabel,
    height: deviceForm.height,
    customType: finalCustomType,
    memo: stripLinkedAssetMemo(deviceForm.memo),
    hardwareInfo: cleanHardwareInfo,
    specs: cleanSpecs,
    sourceRef: deviceForm.sourceRef || null,
    quickLinks: quickLinkParsed.links
  }

  // 여백(Gap)에 새 장비 추가 시
  if (oldItem.type === 'gap') {
    if (oldItem.height < newItem.height) {
      message.error(t('rackDiagram.msg.deviceLargerThanGap'))
      return
    }
    const remainingHeight = oldItem.height - newItem.height
    rack.items.splice(iIndex, 1, newItem)
    if (remainingHeight > 0) {
      rack.items.splice(iIndex + 1, 0, { type: 'gap', height: remainingHeight })
    }
  } else {
    // 기존 장비 수정 시 (버그 픽스된 부분)
    const heightDiff = newItem.height - oldItem.height // 높이 변화량

    if (heightDiff === 0) {
      // 높이 변화가 없으면 그냥 교체
      rack.items.splice(iIndex, 1, newItem)
    } else if (heightDiff > 0) {
      // 장비 크기를 늘렸을 때: 아래쪽 여백(Gap)을 그만큼 깎아냄
      const nextItem = rack.items[iIndex + 1]
      if (nextItem && nextItem.type === 'gap' && nextItem.height >= heightDiff) {
        rack.items.splice(iIndex, 1, newItem)
        nextItem.height -= heightDiff
        if (nextItem.height === 0) rack.items.splice(iIndex + 1, 1) // 여백이 0이 되면 삭제
      } else {
        message.error(t('rackDiagram.msg.notEnoughGapBelow'))
        return
      }
    } else {
      // 장비 크기를 줄였을 때: 아래쪽에 남는 만큼 여백(Gap) 추가
      const absDiff = Math.abs(heightDiff)
      rack.items.splice(iIndex, 1, newItem)
      const nextItem = rack.items[iIndex + 1]

      if (nextItem && nextItem.type === 'gap') {
        nextItem.height += absDiff // 이미 여백이 있으면 합치기
      } else {
        rack.items.splice(iIndex + 1, 0, { type: 'gap', height: absDiff }) // 없으면 새로 여백 생성
      }
    }
  }
  if (selectedDeviceRackIndex.value === rIndex && selectedDeviceItemIndex.value === iIndex && rack.items[iIndex]?.type !== 'gap') {
    syncSelectedDeviceDraft(rack.items[iIndex])
  }
  closeDeviceModal()
}

const cloneItem = (rIndex, iIndex) => {
  const rack = parsedRacks.value[rIndex]
  const itemToClone = rack.items[iIndex]
  const neededHeight = itemToClone.height
  const newItem = { ...itemToClone }

  const placeIntoGap = (gapIndex, placeAtBottom = false) => {
    const gap = rack.items[gapIndex]
    if (!gap || gap.type !== 'gap' || gap.height < neededHeight) return false
    const remain = gap.height - neededHeight
    if (placeAtBottom) {
      const insert = []
      if (remain > 0) insert.push({ type: 'gap', height: remain })
      insert.push(newItem)
      rack.items.splice(gapIndex, 1, ...insert)
    } else {
      rack.items.splice(gapIndex, 1, newItem)
      if (remain > 0) rack.items.splice(gapIndex + 1, 0, { type: 'gap', height: remain })
    }
    return true
  }

  // 1) 아래쪽 어디든 충분한 gap 우선
  for (let i = iIndex + 1; i < rack.items.length; i++) {
    if (rack.items[i].type === 'gap' && placeIntoGap(i, false)) return
  }

  // 2) 위쪽 어디든 충분한 gap (원본 근처에 붙도록 아래쪽 정렬)
  for (let i = iIndex - 1; i >= 0; i--) {
    if (rack.items[i].type === 'gap' && placeIntoGap(i, true)) return
  }

  // 3) 그래도 없으면 랙 전체 검사(방어)
  for (let i = 0; i < rack.items.length; i++) {
    if (rack.items[i].type === 'gap' && placeIntoGap(i, false)) return
  }

  message.error(t('rackDiagram.msg.notEnoughSpaceInRack', { height: neededHeight }))
}

const deleteItem = (rIndex, iIndex) => {
  const rack = parsedRacks.value[rIndex]
  const itemHeight = rack.items[iIndex].height

  // 삭제하는 장비를 Gap으로 변경
  rack.items.splice(iIndex, 1, { type: 'gap', height: itemHeight })

  // 연속된 Gap 병합 (위아래 빈 공간 합치기)
  for (let i = rack.items.length - 1; i > 0; i--) {
    if (rack.items[i].type === 'gap' && rack.items[i - 1].type === 'gap') {
      rack.items[i - 1].height += rack.items[i].height
      rack.items.splice(i, 1)
    }
  }

  if (selectedDeviceRackIndex.value === rIndex && selectedDeviceItemIndex.value === iIndex) {
    selectedDeviceRackIndex.value = -1
    selectedDeviceItemIndex.value = -1
  }
}

// ---------------- 장비 이동(이사) 로직 ----------------
const moveModalVisible = ref(false)
const moveTargetRackIndex = ref(null) // 사용자가 선택할 도착지 랙
const moveSourceInfo = reactive({ rIndex: -1, iIndex: -1 }) // 이사 갈 장비의 원래 위치

// 이사 모달 열기
const openMoveDeviceModal = (rIndex, iIndex) => {
  // 랙이 하나밖에 없으면 이사 불가
  if (parsedRacks.value.length <= 1) {
    message.warning(t('rackDiagram.msg.noOtherRackToMove'))
    return
  }

  moveSourceInfo.rIndex = rIndex
  moveSourceInfo.iIndex = iIndex
  moveTargetRackIndex.value = null // 초기화
  moveModalVisible.value = true
}

// 이사 실행 (확인 버튼 클릭 시)
const submitMoveDevice = () => {
  if (moveTargetRackIndex.value === null) {
    message.warning(t('rackDiagram.msg.selectTargetRack'))
    return
  }

  const sourceRIndex = moveSourceInfo.rIndex
  const sourceIIndex = moveSourceInfo.iIndex
  const targetRIndex = moveTargetRackIndex.value

  const sourceRack = parsedRacks.value[sourceRIndex]
  const targetRack = parsedRacks.value[targetRIndex]
  const deviceToMove = { ...sourceRack.items[sourceIIndex] } // 장비 복사

  // 1. 대상 랙에 들어갈 공간(Gap)이 있는지 탐색
  // (장비 높이보다 크거나 같은 첫 번째 여백을 찾음)
  const targetGapIndex = targetRack.items.findIndex(
    item => item.type === 'gap' && item.height >= deviceToMove.height
  )

  if (targetGapIndex === -1) {
    message.error(t('rackDiagram.msg.targetRackNoSpace', { rack: targetRack.name, height: deviceToMove.height }))
    return
  }

  // 2. [대상 랙] 처리: 찾은 여백을 쪼개서 장비 넣기
  const targetGap = targetRack.items[targetGapIndex]
  const remainingHeight = targetGap.height - deviceToMove.height

  // 여백 위치에 장비 덮어쓰기
  targetRack.items.splice(targetGapIndex, 1, deviceToMove)

  // 남은 공간이 있다면 그 아래에 다시 여백 추가
  if (remainingHeight > 0) {
    targetRack.items.splice(targetGapIndex + 1, 0, { type: 'gap', height: remainingHeight })
  }

  // 3. [출발 랙] 처리: 장비가 떠난 자리를 여백으로 메꾸고 병합
  // 기존 장비 삭제 및 Gap으로 변환
  sourceRack.items.splice(sourceIIndex, 1, { type: 'gap', height: deviceToMove.height })

  // 연속된 Gap 병합 (위아래 싹 훑어서 합침)
  // (Tip: 역순으로 돌면서 합쳐야 인덱스가 꼬이지 않음)
  for (let i = sourceRack.items.length - 1; i > 0; i--) {
    const current = sourceRack.items[i]
    const above = sourceRack.items[i - 1]

    if (current.type === 'gap' && above.type === 'gap') {
      above.height += current.height
      sourceRack.items.splice(i, 1) // 현재 Gap 제거 (위쪽 Gap에 흡수됨)
    }
  }

  message.success(t('rackDiagram.msg.deviceMoved', { label: deviceToMove.label, rack: targetRack.name }))
  moveModalVisible.value = false
}

// 종류별 아이콘 정의
const getDeviceIcon = (type) => {
  const icons = {
    // 내부가 더 꽉 차 보이는 Solid/Bold 스타일 SVG
    server: `<svg viewBox="0 0 24 24" fill="currentColor" stroke="none"><path d="M20 10H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2zm-10-5h2v2h-2V5zm6 0h2v2h-2V5zM20 22H4a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2zm-10-5h2v2h-2v-2zm6 0h2v2h-2v-2z"/></svg>`,
    switch: `<svg viewBox="0 0 24 24" fill="currentColor" stroke="none"><rect x="2" y="5" width="20" height="14" rx="2"/><circle cx="6" cy="12" r="1.5" fill="black"/><circle cx="12" cy="12" r="1.5" fill="black"/><circle cx="18" cy="12" r="1.5" fill="black"/></svg>`,
    storage: `<svg viewBox="0 0 24 24" fill="currentColor" stroke="none"><path d="M12 2C6.48 2 2 3.8 2 6v12c0 2.2 4.48 4 10 4s10-1.8 10-4V6c0-2.2-4.48-4-10-4zm0 18c-4.41 0-8-1.34-8-3s3.59-3 8-3 8 1.34 8 3-3.59 3-8 3zm0-10c-4.41 0-8-1.34-8-3s3.59-3 8-3 8 1.34 8 3-3.59 3-8 3z"/></svg>`,
    blade: `<svg viewBox="0 0 24 24" fill="currentColor"><rect x="3" y="4" width="4" height="16" rx="1"/><rect x="9" y="4" width="4" height="16" rx="1"/><rect x="15" y="4" width="4" height="16" rx="1"/></svg>`,
    firewall: `<svg viewBox="0 0 24 24" fill="currentColor" stroke="none"><path d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z"/></svg>`,
    router: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 3l4 4h-3v4h-2V7H8l4-4zM5 13h14a2 2 0 012 2v3a2 2 0 01-2 2H5a2 2 0 01-2-2v-3a2 2 0 012-2zm2 3a1 1 0 100 2 1 1 0 000-2z"/></svg>`,
    loadbalancer: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M5 5h4v4H5V5zm10 0h4v4h-4V5zM5 15h4v4H5v-4zm7-7l3 3h-2v2h2l-3 3-3-3h2v-2H9l3-3z"/></svg>`,
    nas: `<svg viewBox="0 0 24 24" fill="currentColor"><rect x="4" y="3" width="16" height="18" rx="2"/><rect x="7" y="6" width="10" height="3" fill="white" opacity=".45"/><circle cx="9" cy="16" r="1.3" fill="white" opacity=".55"/><circle cx="15" cy="16" r="1.3" fill="white" opacity=".55"/></svg>`,
    monitoring: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M3 5h18v12H3V5zm3 7h3l2-4 3 7 2-3h2v-2h-3l-1 1.5L11 5 8 10H6v2zm3 7h6v2H9v-2z"/></svg>`,
    kvm: `<svg viewBox="0 0 24 24" fill="currentColor"><rect x="3" y="4" width="18" height="11" rx="1"/><rect x="6" y="17" width="12" height="2" rx="1"/><rect x="8" y="20" width="8" height="1"/></svg>`,
    ups: `<svg viewBox="0 0 24 24" fill="currentColor" stroke="none"><path d="M13 2.05v9.45h4.5l-8.5 10.45v-9.45H4.5L13 2.05z"/></svg>`,
    patch: `<svg viewBox="0 0 24 24" fill="currentColor" stroke="none"><path d="M22 7H2v10h20V7zm-14 6H4v-2h4v2zm6 0h-4v-2h4v2zm6 0h-4v-2h4v2z"/></svg>`,
    pdu: `<svg viewBox="0 0 24 24" fill="currentColor" stroke="none"><rect x="7" y="2" width="10" height="20" rx="2"/><circle cx="12" cy="6" r="1.5" fill="black"/><circle cx="12" cy="12" r="1.5" fill="black"/><circle cx="12" cy="18" r="1.5" fill="black"/></svg>`,
    cooling: `<svg viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="12" r="3"/><path d="M12 2a5 5 0 014 8c-2 0-4-1-5-3-.6-1.4-.2-3.2 1-5zM22 12a5 5 0 01-8 4c0-2 1-4 3-5 1.4-.6 3.2-.2 5 1zM12 22a5 5 0 01-4-8c2 0 4 1 5 3 .6 1.4.2 3.2-1 5zM2 12a5 5 0 018-4c0 2-1 4-3 5-1.4.6-3.2.2-5-1z"/></svg>`,
    blank: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="7" width="16" height="10" rx="2"/><line x1="7" y1="12" x2="17" y2="12"/></svg>`,
    custom: `<svg viewBox="0 0 24 24" fill="currentColor" stroke="none"><path d="M12 2L1 21h22L12 2zm0 4.19L19.53 19H4.47L12 6.19z"/></svg>`
  }
  return icons[type] || icons.custom
}

const renderBadgeIcon = (type) => getDeviceIcon(type)

const getDevicePanelType = (item) => {
  const text = `${item?.type || ''} ${item?.customType || ''} ${item?.label || ''}`.toLowerCase()
  if (text.includes('blade') || text.includes('블레이드')) return 'blade'
  if (text.includes('router') || text.includes('라우터')) return 'router'
  if (text.includes('load') || text.includes('balance') || text.includes('로드')) return 'loadbalancer'
  if (text.includes('nas')) return 'nas'
  if (text.includes('monitor') || text.includes('모니터')) return 'monitoring'
  if (text.includes('kvm') || text.includes('console') || text.includes('콘솔')) return 'kvm'
  if (text.includes('cool') || text.includes('fan') || text.includes('쿨링')) return 'cooling'
  if (text.includes('patch') || text.includes('패치')) return 'patch'
  if (text.includes('firewall') || text.includes('방화벽')) return 'firewall'
  if (text.includes('switch') || text.includes('스위치')) return 'switch'
  if (text.includes('storage') || text.includes('스토리지')) return 'storage'
  if (text.includes('pdu')) return 'pdu'
  if (text.includes('ups')) return 'ups'
  if (text.includes('server') || text.includes('서버') || text.includes('host') || text.includes('agent')) return 'server'
  return item?.type || 'custom'
}

const PANEL_VIEW_WIDTH = 760
const PANEL_UNIT_HEIGHT = RACK_UNIT_HEIGHT
const PANEL_GAP = 8
const PANEL_PAD_X = 12
const PANEL_PAD_Y = 6
const DETAIL = {
  fill: 'detail-fill',
  mid: 'detail-mid',
  dark: 'detail-dark',
  line: 'detail-line'
}

const n = (value) => Number(value.toFixed(2))
const svgRect = (x, y, w, h, cls = DETAIL.mid, rx = 2) => `<rect x="${n(x)}" y="${n(y)}" width="${n(w)}" height="${n(h)}" rx="${rx}" class="${cls}" />`
const svgCircle = (cx, cy, r, cls = DETAIL.dark) => `<circle cx="${n(cx)}" cy="${n(cy)}" r="${n(r)}" class="${cls}" />`
const svgLine = (x1, y1, x2, y2, cls = DETAIL.line) => `<line x1="${n(x1)}" y1="${n(y1)}" x2="${n(x2)}" y2="${n(y2)}" class="${cls}" />`
const svgPolyline = (points, cls = DETAIL.line) => `<polyline points="${points.map(([x, y]) => `${n(x)},${n(y)}`).join(' ')}" class="${cls}" />`
const repeat = (count, renderer) => Array.from({ length: count }, (_, index) => renderer(index)).join('')

const svgPort = (x, y, w = 16, h = 12) => `${svgRect(x, y, w, h, 'port-shell', 1.5)}${svgRect(x + 3, y + 3, w - 6, h - 5, 'port-hole', 0.5)}`
const svgOutlet = (x, y, w = 28, h = 22) => `<g>${svgRect(x, y, w, h, 'outlet-shell', 4)}${svgRect(x + w * 0.32, y + h * 0.28, 3, h * 0.44, 'port-hole', 1)}${svgRect(x + w * 0.58, y + h * 0.28, 3, h * 0.44, 'port-hole', 1)}</g>`
const svgPlug = (x, y, w = 34, h = 28) => {
  const cut = Math.min(4.5, w * 0.12)
  const points = [
    [x + cut, y],
    [x + w - cut, y],
    [x + w, y + cut],
    [x + w, y + h - cut],
    [x + w - cut, y + h],
    [x + cut, y + h],
    [x, y + h - cut],
    [x, y + cut]
  ].map(([px, py]) => `${n(px)},${n(py)}`).join(' ')
  const slotW = Math.max(1.8, w * 0.08)
  const slotH = Math.max(7, h * 0.3)
  const slotY = y + h * 0.3
  const slot1X = x + w * 0.36
  const slot2X = x + w * 0.56
  const groundDotX = x + w / 2
  const groundDotY = y + h * 0.76
  return `<g><polygon points="${points}" class="plug-shell" />${svgRect(slot1X, slotY, slotW, slotH, 'plug-slot', 0.8)}${svgRect(slot2X, slotY, slotW, slotH, 'plug-slot', 0.8)}${svgCircle(groundDotX, groundDotY, 1.2, 'plug-slot')}</g>`
}

const rowMetric = (item) => {
  const rawRowSpan = Number(item?.height || item?.rowSpan || 1)
  const rowSpan = Number.isFinite(rawRowSpan) ? Math.max(1, rawRowSpan) : 1
  const viewHeight = rowSpan * PANEL_UNIT_HEIGHT
  return {
    rowSpan,
    viewHeight,
    panelX: PANEL_PAD_X,
    panelY: PANEL_PAD_Y,
    panelW: PANEL_VIEW_WIDTH - PANEL_PAD_X * 2,
    panelH: viewHeight - PANEL_GAP
  }
}
const sizeTier = (rowSpan) => (rowSpan >= 3 ? 'large' : (rowSpan >= 2 ? 'medium' : 'compact'))
const centerY = (panelY, panelH, h) => Math.max(panelY + 2, panelY + (panelH - h) / 2)
const centerX = (panelX, panelW, w) => panelX + (panelW - w) / 2
const densityByHeight = (panelH, base, step = 32) => Math.max(base, base + Math.floor((panelH - 44) / step))
const fitRange = (v, min, max) => Math.max(min, Math.min(max, v))

const slots = (x, y, count, slotW, slotH, gap, led = true) => repeat(count, i => {
  const sx = x + i * (slotW + gap)
  return `${svgRect(sx, y, slotW, slotH, DETAIL.fill, 1.5)}${svgRect(sx + 2, y + 2, slotW - 4, Math.max(4, slotH * 0.2), 'detail-soft', 1)}${led ? svgCircle(sx + slotW / 2, y + slotH - 6, 2, DETAIL.dark) : ''}`
})

const vents = (x, y, count, h, gap = 9, w = 4) => repeat(count, i => svgRect(x + i * gap, y, w, h, DETAIL.mid, 1))
const fan = (cx, cy, rX, rY) => `<g>${`<ellipse cx="${n(cx)}" cy="${n(cy)}" rx="${n(rX)}" ry="${n(rY)}" class="fan-ring" />`}${`<ellipse cx="${n(cx)}" cy="${n(cy)}" rx="${n(rX * 0.18)}" ry="${n(rY * 0.18)}" class="${DETAIL.mid}" />`}${svgLine(cx - rX * 0.82, cy, cx + rX * 0.82, cy, 'fan-line')}${svgLine(cx, cy - rY * 0.82, cx, cy + rY * 0.82, 'fan-line')}${svgLine(cx - rX * 0.58, cy - rY * 0.58, cx + rX * 0.58, cy + rY * 0.58, 'fan-line')}${svgLine(cx - rX * 0.58, cy + rY * 0.58, cx + rX * 0.58, cy - rY * 0.58, 'fan-line')}</g>`

const renderStorageDetails = ({ panelX, panelY, panelW, panelH, rowSpan }) => {
  const tier = sizeTier(rowSpan)
  const count = fitRange(densityByHeight(panelH, 14, 34), 12, 22)
  const slotH = tier === 'compact' ? 28 : (tier === 'medium' ? 44 : 62)
  const slotW = tier === 'large' ? 18 : 20
  const gap = fitRange((panelW - count * slotW) / Math.max(1, (count - 1)), 8, 14)
  const totalW = count * slotW + (count - 1) * gap
  const x = panelX + (panelW - totalW) / 2
  const y = centerY(panelY, panelH, slotH)
  return slots(x, y, count, slotW, slotH, gap, true)
}

const renderServerDetails = ({ panelX, panelY, panelW, panelH, rowSpan }) => {
  const tier = sizeTier(rowSpan)
  const bandH = tier === 'compact' ? 28 : (tier === 'medium' ? 40 : 56)
  const y = centerY(panelY, panelH, bandH)
  const cy = panelY + panelH / 2
  const driveY = y + 2
  const portY = y + bandH - 13
  const ventCount = fitRange(densityByHeight(panelH, 8, 40), 8, 14)
  const driveCount = tier === 'large' ? 4 : 3
  return `${vents(panelX + 32, y + 4, ventCount, 32, 10, 5)}${svgCircle(panelX + 172, cy, 4, 'fan-ring')}${svgCircle(panelX + 198, cy, 2.3, DETAIL.dark)}${repeat(driveCount, i => svgRect(panelX + panelW - 205 + i * 52, driveY, 42, 14, 'drive'))}${repeat(driveCount, i => svgPort(panelX + panelW - 205 + i * 52, portY, 38, 11))}`
}

const renderBladeDetails = ({ panelX, panelY, panelW, panelH, rowSpan }) => {
  const tier = sizeTier(rowSpan)
  const count = fitRange(densityByHeight(panelH, 20, 26), 20, 34)
  const slotW = 13
  const gap = fitRange((panelW - count * slotW) / Math.max(1, (count - 1)), 5, 10)
  const slotH = tier === 'compact' ? 28 : (tier === 'medium' ? 42 : 60)
  const totalW = count * slotW + (count - 1) * gap
  const x = panelX + (panelW - totalW) / 2
  const y = centerY(panelY, panelH, slotH)
  return repeat(count, i => {
    const sx = x + i * (slotW + gap)
    return `${svgRect(sx, y, slotW, slotH, 'blade-slot', 1)}${svgRect(sx + 2, y + 4, slotW - 4, 5, 'detail-soft', 0.5)}${svgCircle(sx + slotW / 2, y + slotH - 6, 1.8, DETAIL.dark)}`
  })
}

const renderSwitchDetails = ({ panelX, panelY, panelW, panelH, rowSpan }) => {
  const tier = sizeTier(rowSpan)
  const portW = 16
  const portH = tier === 'compact' ? 9 : 10
  const rowGap = tier === 'compact' ? 5 : 6
  const startY = centerY(panelY, panelH, portH * 2 + rowGap)
  const cols = fitRange(densityByHeight(panelH, 10, 34), 10, 16)
  const gapX = fitRange((panelW - 260 - cols * portW) / Math.max(1, cols - 1), 7, 13)
  const row2cols = Math.max(8, cols - 2)
  return `${repeat(cols, i => svgPort(panelX + 38 + i * (portW + gapX), startY, portW, portH))}${repeat(row2cols, i => svgPort(panelX + 38 + i * (portW + gapX), startY + portH + rowGap, portW, portH))}${repeat(tier === 'large' ? 4 : 3, i => svgPort(panelX + panelW - 122 + i * 28, panelY + panelH / 2 - 7, 20, 14))}`
}

const renderPatchPanelDetails = ({ panelX, panelY, panelW, panelH, rowSpan }) => {
  const count = fitRange(densityByHeight(panelH, 18, 32), 18, 30)
  const portW = 15
  const gap = fitRange((panelW - count * portW) / Math.max(1, count - 1), 6, 11)
  const totalW = count * portW + (count - 1) * gap
  const x = panelX + (panelW - totalW) / 2
  const y = panelY + panelH / 2 - 7
  return repeat(count, i => svgPort(x + i * (portW + gap), y, portW, 14))
}

const renderRouterDetails = ({ panelX, panelY, panelW, panelH, rowSpan }) => {
  const tier = sizeTier(rowSpan)
  const portCount = tier === 'large' ? 7 : 6
  return `${svgCircle(panelX + 38, panelY + panelH / 2, 3, DETAIL.dark)}${svgCircle(panelX + 60, panelY + panelH / 2, 3, DETAIL.dark)}${svgCircle(panelX + 82, panelY + panelH / 2, 3, DETAIL.dark)}${vents(panelX + 140, panelY + panelH / 2 - 16, tier === 'large' ? 8 : 7, 32, 14, 5)}${svgPolyline([[panelX + 255, panelY + panelH / 2], [panelX + 300, panelY + panelH / 2], [panelX + 322, panelY + panelH / 2 - 12]], 'detail-line')}${repeat(portCount, i => svgPort(panelX + panelW - 205 + i * 28, panelY + panelH / 2 - 7, 20, 14))}`
}

const renderFirewallDetails = ({ panelX, panelY, panelW, panelH, rowSpan }) => {
  const tier = sizeTier(rowSpan)
  const baseX = panelX + 165
  const baseY = panelY + panelH / 2 - 20
  const portCount = tier === 'large' ? 6 : 5
  return `${svgCircle(panelX + 42, panelY + panelH / 2, 4, DETAIL.dark)}${repeat(6, i => svgRect(baseX + i * 18, baseY + (5 - i) * 3, 14, 26 - Math.abs(i - 3) * 3, DETAIL.mid, 1))}${repeat(5, i => svgRect(baseX + 130 + i * 18, baseY + i * 3, 14, 22 - i * 2, DETAIL.fill, 1))}${repeat(portCount, i => svgPort(panelX + panelW - 200 + i * 30, panelY + panelH / 2 - 7, 22, 14))}`
}

const renderLoadBalancerDetails = ({ panelX, panelY, panelW, panelH, rowSpan }) => {
  const tier = sizeTier(rowSpan)
  const cy = panelY + panelH / 2
  const outputs = tier === 'large' ? 8 : 6
  return `${repeat(5, i => svgCircle(panelX + 38 + i * 28, cy, 3, DETAIL.dark))}${svgPolyline([[panelX + 190, cy], [panelX + 255, cy], [panelX + 305, cy - 18]], 'detail-line')}${svgPolyline([[panelX + 255, cy], [panelX + 305, cy]], 'detail-line')}${svgPolyline([[panelX + 255, cy], [panelX + 305, cy + 18]], 'detail-line')}${repeat(outputs, i => svgCircle(panelX + panelW - 175 + i * 20, cy, 2.5, DETAIL.dark))}${svgCircle(panelX + panelW - 32, cy, 7, 'fan-ring')}`
}

const renderNasDetails = ({ panelX, panelY, panelW, panelH, rowSpan }) => {
  const tier = sizeTier(rowSpan)
  const driveH = tier === 'compact' ? 28 : (tier === 'medium' ? 36 : 52)
  const y = centerY(panelY, panelH, driveH)
  const bays = tier === 'large' ? 6 : 5
  return `${repeat(bays, i => `${svgRect(panelX + 32 + i * 48, y, 34, driveH, 'drive')}${svgRect(panelX + 39 + i * 48, y + 8, 20, 6, 'detail-soft', 1)}${svgCircle(panelX + 49 + i * 48, y + driveH - 8, 2.2, DETAIL.dark)}`)}${vents(panelX + panelW - 205, panelY + panelH / 2 - 20, tier === 'large' ? 15 : 13, 40, 10, 4)}${svgCircle(panelX + panelW - 60, panelY + panelH / 2, 3, DETAIL.dark)}${svgCircle(panelX + panelW - 42, panelY + panelH / 2, 3, DETAIL.dark)}${svgPort(panelX + panelW - 22, panelY + panelH / 2 - 8, 16, 16)}`
}

const renderMonitoringDetails = ({ panelX, panelY, panelW, panelH, rowSpan }) => {
  const tier = sizeTier(rowSpan)
  const cy = panelY + panelH / 2
  const screenX = panelX + 92
  const indicators = tier === 'large' ? 6 : 5
  return `${svgCircle(panelX + 38, cy, 3, DETAIL.dark)}${svgRect(screenX, cy - 16, 150, 32, 'screen')}${svgPolyline([[screenX + 14, cy + 2], [screenX + 32, cy + 2], [screenX + 42, cy - 8], [screenX + 54, cy + 12], [screenX + 70, cy - 13], [screenX + 86, cy + 8], [screenX + 100, cy - 4], [screenX + 118, cy + 2], [screenX + 138, cy + 2]], 'detail-line')}${repeat(indicators, i => svgCircle(panelX + panelW - 168 + i * 24, cy, 3, DETAIL.dark))}${svgPort(panelX + panelW - 30, cy - 9, 18, 18)}`
}

const renderKvmDetails = ({ panelX, panelY, panelW, panelH, rowSpan }) => {
  const tier = sizeTier(rowSpan)
  const keyCols = tier === 'large' ? 4 : 3
  const keyRows = tier === 'large' ? 3 : 3
  const keyW = 13
  const keyH = 12
  const keyGapX = 8
  const keyGapY = 7
  const keyBlockH = keyRows * keyH + (keyRows - 1) * keyGapY
  const keyStartX = panelX + 26
  const keyStartY = centerY(panelY, panelH, keyBlockH)

  const displayW = tier === 'large' ? 132 : 118
  const displayH = tier === 'large' ? 32 : 28
  const displayX = panelX + 172
  const displayY = centerY(panelY, panelH, displayH)

  const rightX = panelX + panelW - 188
  const rightW = 158
  const rightY = panelY + 8
  const rightH = panelH - 16
  const controlBtnCount = tier === 'large' ? 3 : 2
  const portsCount = tier === 'large' ? 4 : 3
  const portW = 20
  const portH = 12
  const portsTotalW = portsCount * portW + (portsCount - 1) * 10
  const portsStartX = centerX(rightX, rightW, portsTotalW)
  const portsY = rightY + rightH - 18

  return `${repeat(keyCols * keyRows, i => svgRect(keyStartX + (i % keyCols) * (keyW + keyGapX), keyStartY + Math.floor(i / keyCols) * (keyH + keyGapY), keyW, keyH, 'key', 1))}${svgRect(displayX, displayY, displayW, displayH, 'screen')}${svgRect(displayX + 12, displayY + 8, displayW - 24, 5, 'detail-soft', 1)}${svgRect(rightX, rightY, rightW, rightH, 'detail-soft', 3)}${repeat(controlBtnCount, i => svgCircle(rightX + 26 + i * 24, rightY + 14, 3.5, DETAIL.dark))}${repeat(controlBtnCount, i => svgRect(rightX + 96 + i * 16, rightY + 10, 11, 8, 'key', 1))}${repeat(portsCount, i => svgPort(portsStartX + i * (portW + 10), portsY, portW, portH))}`
}

const renderPduDetails = ({ panelX, panelY, panelW, panelH, rowSpan }) => {
  const tier = sizeTier(rowSpan)
  const outletW = 30
  const outletH = tier === 'compact' ? 18 : (tier === 'medium' ? 24 : 28)
  const y = centerY(panelY, panelH, outletH)
  const outletCount = tier === 'large' ? 10 : 9
  return `${repeat(outletCount, i => svgOutlet(panelX + 22 + i * 40, y, outletW, outletH))}${svgPort(panelX + panelW - 120, panelY + panelH / 2 - 8, 24, 16)}${svgCircle(panelX + panelW - 72, panelY + panelH / 2, 3, DETAIL.dark)}${svgCircle(panelX + panelW - 48, panelY + panelH / 2, 3, DETAIL.dark)}`
}

const renderUpsStatus = (x, y, w, h) => {
  const batteryW = 60
  const batteryH = 20
  const batteryX = x + 18
  const batteryY = y + h * 0.5 - batteryH / 2
  const waveX = x + w - 72
  const waveY = y + h * 0.5
  return `${svgRect(x, y, w, h, 'screen', 2)}${svgRect(batteryX, batteryY, batteryW, batteryH, 'battery', 2)}${svgRect(batteryX + batteryW, batteryY + 6, 8, 8, 'battery', 1)}${svgRect(x + 20, y + 14, w - 40, 10, 'detail-soft', 1)}${repeat(4, i => svgRect(x + 24 + i * 22, y + h - 16, 14, 7, i < 2 ? DETAIL.mid : DETAIL.dark, 1))}${svgPolyline([[waveX - 28, waveY + 2], [waveX - 16, waveY + 2], [waveX - 10, waveY - 8], [waveX - 2, waveY + 8], [waveX + 10, waveY - 2]], 'detail-line')}`
}

const renderUpsPlugs = (x, y, cols, rows, plugW, plugH, gapX, gapY) => {
  return repeat(cols * rows, i => svgPlug(x + (i % cols) * (plugW + gapX), y + Math.floor(i / cols) * (plugH + gapY), plugW, plugH))
}

const renderUpsDetailsCompact = ({ panelX, panelY, panelW, panelH }) => {
  const cy = panelY + panelH / 2
  const plugW = 28
  const plugH = 22
  const leftX = panelX + 18
  const leftY = cy - plugH / 2
  const statusW = 150
  const statusH = 34
  const statusX = panelX + 170
  const statusY = cy - statusH / 2
  const rightX = panelX + panelW - 188
  return `${renderUpsPlugs(leftX, leftY, 3, 1, plugW, plugH, 10, 10)}${renderUpsStatus(statusX, statusY, statusW, statusH)}${svgRect(rightX, cy - 16, 68, 32, 'drive')}${repeat(8, i => svgRect(rightX + 84 + i * 8, cy - 16, 4, 32, DETAIL.mid, 1))}`
}

const renderUpsDetailsMedium = ({ panelX, panelY, panelW, panelH }) => {
  const leftBoxX = panelX + 18
  const leftBoxY = panelY + 10
  const leftBoxW = 195
  const leftBoxH = panelH - 20
  const plugW = 45
  const plugH = 24
  const gapX = 10
  const gapY = 12
  const plugGroupW = plugW * 3 + gapX * 2
  const plugStartX = centerX(leftBoxX, leftBoxW, plugGroupW)
  const plugStartY = centerY(leftBoxY, leftBoxH, plugH * 2 + gapY)
  const statusW = 184
  const statusH = 66
  const statusX = panelX + 238
  const statusY = centerY(panelY, panelH, statusH)
  const rightBoxX = panelX + panelW - 214
  const rightBoxY = panelY + 14
  const rightBoxH = panelH - 28
  return `${svgRect(leftBoxX, leftBoxY, leftBoxW, leftBoxH, 'detail-soft', 4)}${renderUpsPlugs(plugStartX, plugStartY, 3, 2, plugW, plugH, gapX, gapY)}${renderUpsStatus(statusX, statusY, statusW, statusH)}${svgRect(rightBoxX, rightBoxY, 78, rightBoxH, 'drive')}${svgRect(rightBoxX + 12, rightBoxY + 12, 54, 10, 'detail-soft', 1)}${repeat(11, i => svgRect(rightBoxX + 92 + i * 9, rightBoxY, 4, rightBoxH, DETAIL.mid, 1))}`
}

const renderUpsDetailsLarge = ({ panelX, panelY, panelW, panelH }) => {
  const leftBoxX = panelX + 18
  const leftBoxH = 118
  const leftBoxY = centerY(panelY, panelH, leftBoxH)
  const leftBoxW = 210
  const plugW = 60
  const plugH = 26
  const gapX = 10
  const gapY = 10
  const plugGroupW = plugW * 3 + gapX * 2
  const plugStartX = centerX(leftBoxX, leftBoxW, plugGroupW)
  const plugStartY = centerY(leftBoxY, leftBoxH, plugH * 2 + gapY)
  const statusW = 198
  const statusH = 88
  const statusX = panelX + 240
  const statusY = centerY(panelY, panelH, statusH)
  const rightBoxX = panelX + panelW - 222
  const rightBoxY = panelY + 16
  const rightBoxH = panelH - 32
  const ventsX = rightBoxX + 92
  return `${svgRect(leftBoxX, leftBoxY, leftBoxW, leftBoxH, 'detail-soft', 4)}${renderUpsPlugs(plugStartX, plugStartY, 3, 2, plugW, plugH, gapX, gapY)}${repeat(3, i => svgCircle(plugStartX + 10 + i * 46, leftBoxY + leftBoxH - 10, 2, DETAIL.dark))}${renderUpsStatus(statusX, statusY, statusW, statusH)}${repeat(4, i => svgRect(statusX + 20 + i * 24, statusY + statusH - 14, 14, 7, DETAIL.dark, 1))}${svgRect(rightBoxX, rightBoxY, 82, rightBoxH, 'drive')}${svgRect(rightBoxX + 12, rightBoxY + 14, 58, 11, 'detail-soft', 1)}${svgRect(rightBoxX + 16, rightBoxY + rightBoxH - 34, 22, 10, DETAIL.dark, 1)}${svgRect(rightBoxX + 44, rightBoxY + rightBoxH - 34, 22, 10, DETAIL.dark, 1)}${repeat(12, i => svgRect(ventsX + i * 9, rightBoxY, 4, rightBoxH, DETAIL.mid, 1))}${repeat(3, i => svgCircle(ventsX + 8 + i * 36, rightBoxY + rightBoxH - 6, 2.2, DETAIL.dark))}`
}

const renderUpsDetails = (metric) => {
  if (metric.rowSpan >= 3) return renderUpsDetailsLarge(metric)
  if (metric.rowSpan >= 2) return renderUpsDetailsMedium(metric)
  return renderUpsDetailsCompact(metric)
}

const renderCoolingDetails = ({ panelX, panelY, panelW, panelH, rowSpan }) => {
  const tier = sizeTier(rowSpan)
  const r = tier === 'compact' ? 12 : (tier === 'medium' ? 18 : 24)
  const cy = panelY + panelH / 2
  // compensate non-uniform x/y scaling from preserveAspectRatio="none"
  const frameWpx = 560
  const panelLeftPx = 74
  const panelRightPx = 12
  const panelWpx = frameWpx - panelLeftPx - panelRightPx
  const panelHpx = rowSpan * RACK_UNIT_HEIGHT - 14
  const scaleX = panelWpx / PANEL_VIEW_WIDTH
  const scaleY = panelHpx / (rowSpan * PANEL_UNIT_HEIGHT)
  const rx = scaleX > 0 ? r * (scaleY / scaleX) : r
  const fanCount = tier === 'large' ? 4 : 3
  return `${repeat(fanCount, i => fan(panelX + 90 + i * 130, cy, rx, r))}${vents(panelX + panelW - 150, cy - 28, tier === 'large' ? 14 : 12, 56, 11, 4)}`
}

const renderCustomDetails = ({ panelX, panelY, panelW, panelH }) => {
  const slotH = 34
  const y = centerY(panelY, panelH, slotH)
  return `${slots(panelX + 34, y, 12, 18, slotH, 12, false)}${svgRect(panelX + panelW - 180, panelY + panelH / 2 - 16, 110, 32, 'screen')}${svgCircle(panelX + panelW - 36, panelY + panelH / 2, 7, 'fan-ring')}`
}

const renderBlankDetails = ({ panelX, panelY, panelW, panelH }) => {
  const insetX = panelX + 18
  const insetY = panelY + 10
  const insetW = panelW - 36
  const insetH = panelH - 20
  const seamY = panelY + panelH / 2
  return `${svgRect(insetX, insetY, insetW, insetH, 'detail-soft', 5)}${svgRect(insetX + 24, seamY - 1, insetW - 48, 2, 'detail-line', 1)}`
}

const renderDevicePanel = (item) => {
  const type = getDevicePanelType(item)
  const metric = rowMetric(item)
  const renderers = {
    storage: renderStorageDetails,
    server: renderServerDetails,
    blade: renderBladeDetails,
    switch: renderSwitchDetails,
    patch: renderPatchPanelDetails,
    router: renderRouterDetails,
    firewall: renderFirewallDetails,
    loadbalancer: renderLoadBalancerDetails,
    nas: renderNasDetails,
    monitoring: renderMonitoringDetails,
    kvm: renderKvmDetails,
    pdu: renderPduDetails,
    ups: renderUpsDetails,
    cooling: renderCoolingDetails,
    blank: renderBlankDetails,
    custom: renderCustomDetails
  }
  const body = (renderers[type] || renderCustomDetails)(metric)
  return `<svg class="device-panel-svg" viewBox="0 0 ${PANEL_VIEW_WIDTH} ${metric.viewHeight}" preserveAspectRatio="none" aria-hidden="true">
    <g id="device-${type}">
      <rect x="${metric.panelX}" y="${metric.panelY}" width="${metric.panelW}" height="${metric.panelH}" rx="6" class="panel-shell" />
      ${body}
    </g>
  </svg>`
}

// 각 장비의 상단 라인 컬러와 맞춘 아이콘 컬러 정의
const getIconColor = (type) => {
  const colors = {
    server: '#4a90e2',
    switch: '#27ae60',
    storage: '#8e44ad',
    blade: '#35b7df',
    firewall: '#c0392b',
    router: '#7c4dff',
    loadbalancer: '#d94b9a',
    nas: '#4a90e2',
    monitoring: '#f39c32',
    kvm: '#a44cc5',
    ups: '#00b894', // 민트색
    patch: '#f39c12', // 주황색
    pdu: '#d35400', // 진한 주황색
    cooling: '#40c4e8',
    blank: '#94a3b8',
    custom: '#95a5a6' // 회색
  }
  return colors[type] || '#ffffff'
}

onMounted(() => {
  // 1. Zone 목록을 먼저 가져오고, 성공하면 내부에서 fetchRackData()를 자동으로 실행합니다.
  fetchZonesAndRackData()

  // 2. 창 닫기/새로고침 방지 이벤트 리스너 등록
  window.addEventListener('beforeunload', handleBeforeUnload)
  window.addEventListener('resize', handleResponsiveResize)
  nextTick(() => {
    rackScrollContainer = rackDiagramRootRef.value?.closest('.layout-content') || null
    if (rackScrollContainer) {
      rackScrollContainer.addEventListener('scroll', scheduleSidePaneHeightUpdate, { passive: true })
    }
    if (rackScrollContainer && typeof ResizeObserver !== 'undefined') {
      sidePaneViewportResizeObserver = new ResizeObserver(() => scheduleSidePaneHeightUpdate())
      sidePaneViewportResizeObserver.observe(rackScrollContainer)
    }
    applyResponsiveZoom(true)
    scheduleSidePaneHeightUpdate()
    syncRackMainPaneResizeObserver()
  })
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', handleBeforeUnload)
  window.removeEventListener('resize', handleResponsiveResize)
  if (rackScrollContainer) {
    rackScrollContainer.removeEventListener('scroll', scheduleSidePaneHeightUpdate)
  }
  if (resizeDebounceTimer) clearTimeout(resizeDebounceTimer)
  if (zoomRafId) cancelAnimationFrame(zoomRafId)
  if (zoomApplyRafId) cancelAnimationFrame(zoomApplyRafId)
  if (zoomTransitionRafId) cancelAnimationFrame(zoomTransitionRafId)
  if (sidePaneHeightRafId) cancelAnimationFrame(sidePaneHeightRafId)
  if (rackMainPaneResizeObserver) rackMainPaneResizeObserver.disconnect()
  if (sidePaneViewportResizeObserver) sidePaneViewportResizeObserver.disconnect()
  if (expandedLayoutZoomTimer) clearTimeout(expandedLayoutZoomTimer)
})

</script>

<style scoped>
.rack-diagram-root {
  --rack-brand-primary: #1890ff;
  --rack-brand-primary-bg: rgba(24, 144, 255, 0.08);
  --rack-brand-primary-bg-strong: rgba(24, 144, 255, 0.18);
}

/* 툴바 전체 컨테이너 (좌우 양끝 정렬) */
.toolbar-container {
  display: flex !important;
  justify-content: space-between !important;
  align-items: center !important;

  /* 좁아지면 자연스럽게 줄바꿈되도록 허용 */
  flex-wrap: wrap !important;
  gap: 12px; /* 줄바꿈 되었을 때 위아래 여백 확보 */

  --rack-toolbar-control-height: 22px;
  background: #ffffff;
  padding: 12px 16px;
  border-radius: 8px;
  border: 1px solid #e8e8e8;
  box-shadow: 0 1px 2px rgba(0,0,0,0.06);
  margin-bottom: 16px;
}

.toolbar-view-controls,
.toolbar-left,
.toolbar-actions,
.toolbar-zoom {
  display: flex;
  align-items: center;
  min-height: 32px;
}

.toolbar-detail {
  flex-wrap: nowrap !important;
  justify-content: flex-start !important;
}

.toolbar-detail .toolbar-view-controls {
  min-width: 0;
  order: 1;
}

.toolbar-detail .toolbar-actions {
  order: 2;
  margin-left: auto;
}

.toolbar-view-controls {
  flex: 1 1 auto;
  min-width: 0;
}

.toolbar-actions {
  flex: 0 0 auto;
}

.rack-save-tooltip-wrap {
  display: inline-flex;
}

.toolbar-expand-divider {
  display: block;
  width: 1px;
  height: 20px;
  background: #e5e7eb;
}

.toolbar-expand-btn {
  width: 32px;
  min-width: 32px;
  padding: 0 !important;
  color: #64748b;
  border-radius: 6px;
}

.toolbar-expand-btn:hover,
.toolbar-expand-btn:focus {
  color: #1677ff;
  border-color: #91caff;
  background: #e6f4ff;
}

.toolbar-zoom {
  flex: 0 0 auto;
  gap: 8px;
  margin-left: 14px;
  padding-left: 14px;
  border-left: 1px solid #e5e7eb;
}

.toolbar-container :deep(.ant-btn),
.toolbar-container :deep(.ant-input-number) {
  height: 32px;
}

.toolbar-left :deep(.ant-space),
.toolbar-left :deep(.ant-space-item) {
  display: flex;
  align-items: center;
  min-width: 0;
  height: 20px;
}

.toolbar-left :deep(.ant-input-search) {
  width: 100%;
}

.toolbar-left :deep(.ant-input-search .ant-input) {
  height: var(--rack-toolbar-control-height);
  line-height: calc(var(--rack-toolbar-control-height) - 2px);
  padding-top: 4px;
  padding-bottom: 4px;
  box-sizing: border-box;
}

.toolbar-left :deep(.ant-input-search .ant-input-group-addon) {
  height: var(--rack-toolbar-control-height);
  padding: 0;
  line-height: calc(var(--rack-toolbar-control-height) - 2px);
  vertical-align: top;
  box-sizing: border-box;
}

.toolbar-left :deep(.ant-input-search-button) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: var(--rack-toolbar-control-height);
  padding: 0;
  line-height: calc(var(--rack-toolbar-control-height) - 2px);
  box-sizing: border-box;
}

.rack-diagram-root :deep(.ant-input-search > .ant-input-group > .ant-input-group-addon:last-child .ant-input-search-button:not(.ant-btn-primary)) {
  height: 32px;
}

.toolbar-container :deep(.ant-input-number-input) {
  height: var(--rack-toolbar-control-height);
  padding: 0 8px;
  line-height: calc(var(--rack-toolbar-control-height) - 2px);
  box-sizing: border-box;
}

.rack-diagram-root :deep(.ant-input-number-sm input) {
  height: var(--rack-toolbar-control-height);
  padding: 0 8px;
}

.toolbar-container :deep(.ant-btn) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.autogen-action-dropdown__trigger {
  display: inline-block;
}

.autogen-action-dropdown__button {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.rack-toolbar-action-dropdown__content {
  min-width: 156px;
  background: #fff;
  border-radius: 8px;
  border: 1px solid #d9d9d9;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
  padding: 8px;
}

.rack-toolbar-action-list {
  display: flex;
  flex-direction: column;
  width: auto;
  max-width: none;
}

.rack-toolbar-action-list :deep(.ant-tooltip-disabled-compatible-wrapper) {
  width: 100%;
}

.rack-toolbar-action-list :deep(.action-button-item--dataview) {
  display: flex !important;
  align-items: center;
  justify-content: flex-start;
  width: 100%;
  margin-left: 0 !important;
  border: none;
  padding-left: 12px;
  padding-right: 12px;
}

.rack-toolbar-action-list :deep(.action-button-item--dataview:not(.ant-btn-disabled):hover),
.rack-toolbar-action-list :deep(.action-button-item--dataview:not(.ant-btn-disabled):focus) {
  background-color: #e6f4ff;
  border-color: transparent;
  color: #0958d9;
}

.rack-toolbar-action-list :deep(.action-button-item__icon) {
  font-size: 16px;
}

.rack-toolbar-action-list :deep(.action-button-item__label) {
  margin-left: 8px;
  font-weight: 500;
}

.rack-toolbar-action-list :deep(.action-button-item--dataview:not(.ant-btn-disabled):hover .action-button-item__label),
.rack-toolbar-action-list :deep(.action-button-item--dataview:not(.ant-btn-disabled):hover .action-button-item__icon),
.rack-toolbar-action-list :deep(.action-button-item--dataview:not(.ant-btn-disabled):focus .action-button-item__label),
.rack-toolbar-action-list :deep(.action-button-item--dataview:not(.ant-btn-disabled):focus .action-button-item__icon) {
  color: #0958d9;
}

.rack-toolbar-action-divider {
  height: 1px;
  margin: 6px 0;
  background: #f0f0f0;
}

:global(.autogen-action-dropdown .rack-toolbar-action-dropdown__content) {
  min-width: 156px;
  background: #fff;
  border-radius: 8px;
  border: 1px solid #d9d9d9;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
  padding: 8px;
}

:global(.autogen-action-dropdown .rack-toolbar-action-list) {
  display: flex;
  flex-direction: column;
  width: auto;
  max-width: none;
}

:global(.autogen-action-dropdown .rack-toolbar-action-list .ant-tooltip-disabled-compatible-wrapper) {
  width: 100%;
}

:global(.autogen-action-dropdown .rack-toolbar-action-list .action-button-item--dataview) {
  display: flex !important;
  align-items: center;
  justify-content: flex-start;
  width: 100%;
  margin-left: 0 !important;
  border: none;
  padding-left: 12px;
  padding-right: 12px;
}

:global(.autogen-action-dropdown .rack-toolbar-action-list .action-button-item--dataview:not(.ant-btn-disabled):hover),
:global(.autogen-action-dropdown .rack-toolbar-action-list .action-button-item--dataview:not(.ant-btn-disabled):focus) {
  background-color: #e6f4ff;
  border-color: transparent;
  color: #0958d9;
}

:global(.autogen-action-dropdown .rack-toolbar-action-list .action-button-item__icon) {
  font-size: 16px;
}

:global(.autogen-action-dropdown .rack-toolbar-action-list .action-button-item__label) {
  margin-left: 8px;
  font-weight: 500;
}

:global(.autogen-action-dropdown .rack-toolbar-action-list .action-button-item--dataview:not(.ant-btn-disabled):hover .action-button-item__label),
:global(.autogen-action-dropdown .rack-toolbar-action-list .action-button-item--dataview:not(.ant-btn-disabled):hover .action-button-item__icon),
:global(.autogen-action-dropdown .rack-toolbar-action-list .action-button-item--dataview:not(.ant-btn-disabled):focus .action-button-item__label),
:global(.autogen-action-dropdown .rack-toolbar-action-list .action-button-item--dataview:not(.ant-btn-disabled):focus .action-button-item__icon) {
  color: #0958d9;
}

:global(.autogen-action-dropdown .rack-toolbar-action-divider) {
  height: 1px;
  margin: 6px 0;
  background: #f0f0f0;
}

/* 버튼 사이 구분선 스타일 */
.toolbar-divider {
  background-color: #d9d9d9;
  height: 20px;
  margin: 0 4px;
}

/* 화면 배율 텍스트 */
.zoom-input {
  width: 70px;
  text-align: center;
  flex-shrink: 0;
}

.zoom-input :deep(.ant-input-number-input-wrap) {
  height: 100%;
}

.zoom-input :deep(.ant-input-number-input) {
  height: 30px !important;
  padding: 0 8px !important;
  line-height: 30px !important;
  text-align: center;
}

.zoom-slider-wrap {
  width: 144px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  height: 32px;
}

.zoom-slider-wrap :deep(.ant-slider) {
  width: 100%;
  margin: 0;
}

/* 랙 캔버스 배경 */
.rack-canvas {
  overflow: visible;
  min-height: 600px;
  padding: 20px;
  scrollbar-width: thin;
  scrollbar-color: var(--ui-scroll-thumb) var(--ui-scroll-track);
  background: radial-gradient(circle, #f0f2f5 0%, #e6e9ed 100%);
}

.rack-list-view {
  min-height: 600px;
  padding: 8px 4px 12px;
}

.rack-list-summary-card {
  margin-bottom: 14px;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  box-shadow: 0 4px 14px rgba(15, 23, 42, 0.05);
}

.rack-list-summary-card :deep(.ant-card-body) {
  padding: 15px 24px;
}

.rack-list-summary-title {
  color: #111827;
  font-size: 15px;
  font-weight: 700;
  line-height: 1.4;
  flex: 0 0 auto;
  padding-right: 30px;
  margin-right: 16px;
  border-right: 1px solid #eef2f7;
}

.rack-list-summary-metrics {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 16px;
  min-width: 0;
  width: 100%;
}

.rack-list-summary-metric {
  min-width: 140px;
  display: inline-flex;
  align-items: center;
  gap: 12px;
  padding: 0 26px;
  flex: 1 1 150px;
}

.rack-list-summary-metric:nth-of-type(4),
.rack-list-summary-metric:nth-of-type(5) {
  flex-basis: 180px;
  min-width: 170px;
}

.rack-list-summary-metric + .rack-list-summary-metric {
  border-left: 1px solid #eef2f7;
}

.rack-list-summary-label {
  color: #64748b;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.25;
  white-space: nowrap;
}

.rack-list-summary-value {
  color: #0f172a;
  font-size: 19px;
  font-weight: 700;
  line-height: 1.25;
  white-space: nowrap;
}

.rack-list-summary-text {
  display: inline-flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.rack-list-summary-icon {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 36px;
  color: #1677ff;
  background: rgba(22, 119, 255, 0.09);
}

.rack-list-summary-icon :deep(.anticon) {
  font-size: 18px;
}

.rack-list-summary-rack-icon :deep(.rack-card-icon) {
  width: 21px;
  height: 25px;
}

.rack-list-summary-value-row {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  white-space: nowrap;
}

.rack-list-summary-progress {
  display: block;
  width: 48px;
  margin: 0;
  flex: 0 0 48px;
}

.rack-list-summary-progress :deep(.ant-progress-outer) {
  display: block;
  width: 100%;
  padding-right: 0;
  margin-right: 0;
}

.rack-list-summary-progress :deep(.ant-progress-inner) {
  background-color: #e5e7eb;
}

.rack-list-view-switch {
  display: flex;
  justify-content: flex-end;
  margin: -2px 0 12px;
}

.rack-list-view-switch :deep(.ant-radio-button-wrapper) {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.rack-list-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.rack-list-card {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  position: relative;
  min-height: 250px;
  box-shadow: 0 4px 14px rgba(15, 23, 42, 0.04);
  transition: opacity 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease;
}

.rack-list-card:hover {
  border-color: #bfdbfe;
  box-shadow: 0 8px 22px rgba(15, 23, 42, 0.08);
}

.rack-list-card :deep(.ant-card-body) {
  height: 100%;
  padding: 24px 26px;
}

.rack-list-card-actions {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 2;
}

.rack-list-more-btn {
  color: #64748b !important;
  width: 28px;
  height: 28px;
  min-width: 28px;
  padding: 0 !important;
  border-radius: 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  line-height: 1;
}

.rack-list-more-btn:hover {
  color: #334155 !important;
  background: #f1f5f9 !important;
}

.rack-list-more-btn :deep(.anticon) {
  font-size: 20px;
  line-height: 1;
}

.rack-list-more-btn :deep(.anticon-more svg) {
  width: 1em;
  height: 1em;
}

.rack-list-card-match {
  position: absolute;
  top: 10px;
  right: 44px;
  z-index: 1;
  max-width: calc(100% - 72px);
  overflow: hidden;
  white-space: nowrap;
}

.rack-list-card-match :deep(.ant-tag) {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-right: 0;
}

.rack-list-card--matched {
  border-color: #91caff;
  box-shadow: 0 0 0 1px rgba(22, 119, 255, 0.18) inset;
}

.rack-list-card--dimmed {
  opacity: 0.52;
}

.rack-list-card-title {
  font-size: 18px;
  font-weight: 700;
  color: #1f2937;
  line-height: 1.35;
  min-height: 24px;
  max-width: min(100%, 280px);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rack-list-card-body {
  display: flex;
  align-items: flex-start;
  gap: 0;
  height: 100%;
}

.rack-list-card-rack-icon {
  width: 59px;
  height: 92px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 59px;
  margin-right: 12px;
  color: #1f2937;
}

.rack-list-card-rack-icon svg {
  width: 100%;
  height: 100%;
}

.rack-list-card-content {
  flex: 1;
  min-width: 0;
  padding-right: 34px;
}

.rack-list-card-title-row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.rack-list-card-usage {
  margin-top: 24px;
  color: #475569;
  font-size: 14px;
  font-weight: 600;
}

.rack-list-card-progress-row {
  display: grid;
  grid-template-columns: minmax(120px, 1fr) auto;
  align-items: center;
  gap: 28px;
  margin-top: 8px;
}

.rack-list-card-usage-detail {
  color: #475569;
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
}

.rack-list-progress {
  min-width: 0;
  margin: 0;
}

.rack-list-card-stats {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0;
  margin-top: 24px;
  padding-top: 18px;
  border-top: 1px solid #e5e7eb;
}

.rack-list-card-stat {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.rack-list-card-stat + .rack-list-card-stat {
  padding-left: 24px;
  border-left: 1px solid #e5e7eb;
}

.rack-list-card-stat-label {
  color: #64748b;
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
}

.rack-list-card-stat strong {
  color: #0f172a;
  font-size: 16px;
  line-height: 20px;
}

.rack-list-card-footer {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-top: 20px;
  padding-top: 14px;
  border-top: 1px solid #e5e7eb;
  font-size: 12px;
  color: #64748b;
  min-width: 0;
}

.rack-list-card-footer > span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
  white-space: nowrap;
}

.rack-list-card-footer > span:first-child {
  flex: 0 0 auto;
}

.rack-list-card-location {
  flex: 1 1 auto;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
}

.rack-list-add-card {
  min-height: 250px;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.62);
  color: #0f172a;
  cursor: pointer;
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  font-size: 15px;
  font-weight: 700;
  transition: border-color 0.15s ease, background-color 0.15s ease, color 0.15s ease;
}

.rack-list-add-card:hover {
  border-color: #1677ff;
  color: #1677ff;
  background: rgba(22, 119, 255, 0.04);
}

.rack-list-table {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.rack-list-row {
  display: grid;
  grid-template-columns: minmax(260px, 1.45fr) minmax(260px, 1fr) minmax(180px, 0.7fr) 42px;
  align-items: center;
  gap: 18px;
  min-height: 82px;
  padding: 14px 16px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 3px 10px rgba(15, 23, 42, 0.035);
  cursor: pointer;
  transition: opacity 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease, background-color 0.2s ease;
}

.rack-list-row:hover {
  border-color: #bfdbfe;
  box-shadow: 0 6px 16px rgba(15, 23, 42, 0.07);
}

.rack-list-row--matched {
  border-color: #91caff;
  box-shadow: 0 0 0 1px rgba(22, 119, 255, 0.16) inset;
}

.rack-list-row--dimmed {
  opacity: 0.52;
}

.rack-list-row-main {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.rack-list-row-icon {
  width: 34px;
  height: 50px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 34px;
  color: #1f2937;
}

.rack-list-row-icon svg {
  width: 100%;
  height: 100%;
}

.rack-list-row-title-wrap {
  min-width: 0;
}

.rack-list-row-title-line {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.rack-list-row-title {
  display: inline-block;
  max-width: 320px;
  overflow: hidden;
  color: #111827;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rack-list-row-match {
  flex: 0 1 auto;
  max-width: 110px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rack-list-row-meta {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
  margin-top: 7px;
  color: #64748b;
  font-size: 12px;
}

.rack-list-row-meta > span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
  white-space: nowrap;
}

.rack-list-row-meta > span:first-child {
  flex: 0 0 auto;
}

.rack-list-row-location {
  flex: 1 1 auto;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
}

.rack-list-row-usage {
  display: grid;
  grid-template-columns: auto auto minmax(90px, 1fr) auto;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.rack-list-row-label {
  color: #64748b;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.rack-list-row-usage strong {
  color: #0f172a;
  font-size: 18px;
  line-height: 22px;
  white-space: nowrap;
}

.rack-list-row-progress {
  min-width: 0;
  margin: 0;
}

.rack-list-row-used {
  color: #475569;
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
}

.rack-list-row-stats {
  display: flex;
  flex-direction: column;
  gap: 7px;
  color: #64748b;
  font-size: 12px;
  white-space: nowrap;
}

.rack-list-row-stats strong {
  color: #0f172a;
  font-size: 14px;
  margin-left: 4px;
}

.rack-list-row-actions {
  display: flex;
  justify-content: flex-end;
}

.rack-list-add-row {
  min-height: 58px;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.62);
  color: #0f172a;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 700;
  transition: border-color 0.15s ease, background-color 0.15s ease, color 0.15s ease;
}

.rack-list-add-row:hover {
  border-color: #1677ff;
  color: #1677ff;
  background: rgba(22, 119, 255, 0.04);
}

.rack-list-add-icon {
  width: 42px;
  height: 42px;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #1677ff;
  background: rgba(22, 119, 255, 0.10);
  font-size: 22px;
}

.rack-detail-layout {
  display: flex;
  gap: 0;
  align-items: flex-start;
  position: relative;
  min-width: 0;
}

.rack-main-pane {
  flex: 1 1 auto;
  min-width: 0;
  overflow-x: auto;
  overflow-y: visible;
  padding-bottom: 0;
}

.rack-main-pane .rack-zoom-wrapper {
  display: block;
  margin-right: auto;
  margin-left: auto;
}

.rack-side-pane-slot {
  width: clamp(320px, 42%, 460px);
  flex: 0 0 clamp(320px, 42%, 460px);
  margin-left: 12px;
  position: sticky;
  top: 64px;
  align-self: flex-start;
  height: calc(100vh - 80px);
  max-height: calc(100vh - 80px);
  min-height: 0;
  overflow: hidden;
  z-index: 20;
}

.rack-inspector-enter-active,
.rack-inspector-leave-active {
  overflow: hidden;
  will-change: width, flex-basis, transform, opacity;
  transition:
    width 0.28s cubic-bezier(0.22, 1, 0.36, 1),
    flex-basis 0.28s cubic-bezier(0.22, 1, 0.36, 1),
    margin-left 0.28s cubic-bezier(0.22, 1, 0.36, 1),
    opacity 0.2s ease,
    transform 0.28s cubic-bezier(0.22, 1, 0.36, 1);
}

.rack-inspector-enter-from,
.rack-inspector-leave-to {
  width: 0 !important;
  flex-basis: 0 !important;
  margin-left: 0;
  opacity: 0;
  transform: translateX(28px);
}

.rack-inspector-enter-to,
.rack-inspector-leave-from {
  opacity: 1;
  transform: translateX(0);
}

@media (prefers-reduced-motion: reduce) {
  .rack-inspector-enter-active,
  .rack-inspector-leave-active {
    transition: none;
  }
}

.rack-side-pane {
  --device-scrollbar-thumb: var(--ui-scroll-thumb);
  --device-scrollbar-thumb-hover: var(--ui-scroll-thumb-hover);
  width: 100%;
  position: relative;
  align-self: flex-start;
  display: flex;
  flex-direction: column;
  height: 100%;
  max-height: 100%;
  min-height: 0;
  overflow: hidden;
  max-width: 100%;
}

.rack-side-pane-card {
  border-radius: 8px;
  border-color: rgba(0,0,0,0.06);
  box-shadow: 0 1px 2px rgba(0,0,0,0.06);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  height: 100%;
  max-height: 100%;
  min-height: 0;
}

.rack-side-pane-card :deep(.ant-card-head) {
  flex: 0 0 auto;
  position: relative;
  z-index: 2;
  background: #fff;
  border-bottom-color: rgba(0, 0, 0, 0.06);
}

.rack-side-pane-card :deep(.ant-card-body) {
  position: relative;
  display: flex;
  flex-direction: column;
  flex: 1 1 0;
  box-sizing: border-box;
  height: 0;
  max-height: 100%;
  min-height: 0;
  padding-bottom: 64px !important;
  overflow: hidden;
}

.device-summary-header {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  margin-bottom: 14px;
  border: 1px solid rgba(22, 119, 255, 0.12);
  border-radius: 8px;
  background: linear-gradient(135deg, rgba(22, 119, 255, 0.08), rgba(22, 119, 255, 0.02));
  box-shadow: 0 1px 2px rgba(0,0,0,0.04);
}

.device-summary-icon {
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.device-summary-icon :deep(svg) {
  width: 100%;
  height: 100%;
}

.device-summary-main {
  min-width: 0;
  flex: 1;
}

.device-summary-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.device-summary-title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #111827;
  font-size: 16px;
  font-weight: 700;
}

.device-summary-badge {
  flex: 0 0 auto;
  padding: 1px 7px;
  border-radius: 10px;
  background: #f5f5f5;
  color: #595959;
  border: 1px solid #d9d9d9;
  font-size: 11px;
  font-weight: 700;
}

.device-summary-subtitle {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  color: #374151;
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.device-status-inline {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.device-status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #22c55e;
}

.device-info-section {
  border: 1px solid rgba(0,0,0,0.06);
  border-radius: 8px;
  background: #fff;
  padding: 10px 12px;
  margin-bottom: 10px;
  box-shadow: 0 1px 2px rgba(0,0,0,0.06);
}

.device-info-tabs :deep(.ant-tabs-nav) {
  flex: 0 0 auto;
  margin-bottom: 12px;
  border-bottom: 1px solid #e5e7eb;
}

.device-info-tabs {
  flex: 1 1 0;
  height: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.device-info-tabs :deep(.ant-tabs-content-holder) {
  flex: 1 1 auto;
  height: 0;
  min-height: 0;
  overflow: hidden;
}

.device-info-tabs :deep(.ant-tabs-content),
.device-info-tabs :deep(.ant-tabs-tabpane) {
  height: 100%;
  min-height: 0;
}

.device-info-tabs :deep(.ant-tabs-tabpane) {
  overflow-x: hidden;
  overflow-y: auto;
  padding: 0 6px 14px 0;
  scrollbar-width: thin;
  scrollbar-color: var(--device-scrollbar-thumb) transparent;
  scrollbar-gutter: stable;
  overscroll-behavior: contain;
}

.device-info-tabs :deep(.ant-tabs-tabpane::-webkit-scrollbar) {
  width: 6px;
  height: 6px;
}

.device-info-tabs :deep(.ant-tabs-tabpane::-webkit-scrollbar-track) {
  background: transparent;
}

.device-info-tabs :deep(.ant-tabs-tabpane::-webkit-scrollbar-thumb) {
  min-height: 36px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: var(--device-scrollbar-thumb);
  background-clip: padding-box;
}

.device-info-tabs :deep(.ant-tabs-tabpane::-webkit-scrollbar-thumb:hover) {
  background: var(--device-scrollbar-thumb-hover);
  background-clip: padding-box;
}

.device-info-tabs :deep(.ant-tabs-tabpane::-webkit-scrollbar-corner) {
  background: transparent;
}

.device-info-tabs :deep(.ant-tabs-tab) {
  padding: 8px 0;
}

.device-inline-form {
  display: flex;
  flex-direction: column;
  border-top: 1px solid #edf2f7;
}

.device-inline-row {
  display: grid;
  grid-template-columns: 128px minmax(0, 1fr);
  align-items: center;
  min-height: 34px;
  border-bottom: 1px solid #edf2f7;
}

.device-inline-row > label {
  height: 100%;
  display: flex;
  align-items: center;
  padding: 6px 8px;
  margin: 0;
  color: #475569;
  font-size: 13px;
  font-weight: 600;
  background: #f8fafc;
}

.device-inline-row > div,
.device-inline-row > .ant-input,
.device-inline-row > .ant-select {
  min-width: 0;
  margin: 6px 8px;
}

.device-readonly-value {
  color: #334155;
  font-size: 13px;
  word-break: break-word;
}

.device-inline-clickable {
  cursor: pointer;
  border-radius: 4px;
  transition: background 0.15s ease, color 0.15s ease;
}

.device-inline-clickable:hover {
  color: #1677ff;
  background: rgba(22, 119, 255, 0.06);
}

.device-inline-title-input {
  max-width: 220px;
}

.device-info-section-title {
  margin-bottom: 8px;
  color: #111827;
  font-size: 13px;
  font-weight: 700;
}

.device-info-section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.device-info-section-heading .device-info-section-title {
  margin-bottom: 0;
}

.device-section-edit-btn {
  color: #64748b !important;
}

.device-section-edit-btn:hover {
  color: #1677ff !important;
  background: rgba(22, 119, 255, 0.06) !important;
}

.device-link-section-edit-btn {
  float: right;
  margin-top: -4px;
}

.device-info-table {
  border-top: 1px solid #edf2f7;
}

.device-host-compact-table {
  margin-top: 10px;
}

.device-info-table-row {
  display: grid;
  grid-template-columns: 112px minmax(0, 1fr);
  border-bottom: 1px solid #edf2f7;
  min-height: 28px;
}

.device-info-table-row:last-child {
  border-bottom: 0;
}

.device-info-table-row > div {
  padding: 6px 8px;
  min-width: 0;
  color: #334155;
  font-size: 13px;
  word-break: break-word;
}

.device-info-table-row > div:first-child {
  color: #475569;
  font-weight: 600;
  background: #f8fafc;
}

.device-memo-edit-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.device-memo-subtitle {
  color: #475569;
  font-size: 12px;
  font-weight: 700;
}

.device-memo-free-title {
  margin-top: 4px;
}

.device-memo-table {
  border: 1px solid rgba(0,0,0,0.06);
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
}

.device-memo-table-head,
.device-memo-table-row {
  display: grid;
  grid-template-columns: minmax(0, 0.8fr) minmax(0, 1.3fr) 48px;
  align-items: center;
}

.device-memo-table-read .device-memo-table-head,
.device-memo-table-read .device-memo-table-row {
  grid-template-columns: minmax(0, 0.8fr) minmax(0, 1.3fr);
}

.device-memo-table-head {
  background: #f8fafc;
  color: #475569;
  font-size: 12px;
  font-weight: 700;
}

.device-memo-table-head > div,
.device-memo-table-row > * {
  padding: 6px;
  border-right: 1px solid rgba(0,0,0,0.06);
}

.device-memo-table-head > div:last-child,
.device-memo-table-row > *:last-child {
  border-right: 0;
}

.device-memo-table-row {
  border-top: 1px solid rgba(0,0,0,0.06);
  min-height: 34px;
}

.device-memo-table-read .device-memo-table-row:hover {
  background: #fafcff;
}

.device-memo-cell-text {
  min-width: 0;
  color: #334155;
  font-size: 13px;
  line-height: 1.45;
  word-break: break-word;
  background: transparent;
}

.device-spec-number-wrap {
  display: flex;
  align-items: center;
  width: 100%;
  min-width: 0;
}

.device-spec-number-wrap :deep(.ant-input-number) {
  flex: 1 1 auto;
  min-width: 0;
  width: 100%;
}

.device-spec-unit {
  flex: 0 0 auto;
  min-width: 36px;
  height: 24px;
  margin-left: 6px;
  padding: 0 7px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  background: #f8fafc;
  color: #475569;
  font-size: 12px;
  font-weight: 600;
  line-height: 22px;
  text-align: center;
}

.device-memo-row-delete {
  justify-self: center;
}

.device-memo-empty {
  padding: 10px 0;
}

.device-memo-empty :deep(.ant-empty) {
  margin: 0;
}

.device-free-memo-read {
  min-height: 62px;
  padding: 8px 10px;
  border: 1px solid rgba(0,0,0,0.06);
  border-radius: 8px;
  background: #fff;
  color: #334155;
  font-size: 13px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.device-linked-asset-card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 9px;
  border: 1px solid rgba(0,0,0,0.06);
  border-radius: 8px;
  background: #fff;
}

.device-linked-asset-main {
  min-width: 0;
  flex: 1;
}

.device-linked-asset-name {
  color: #334155;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.45;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.device-linked-asset-ip {
  margin-top: 2px;
  color: #64748b;
  font-size: 12px;
  line-height: 1.4;
}

.device-linked-asset-copy {
  flex: 0 0 auto;
}

.device-info-copy-value {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.device-info-copy-value > span,
.device-info-copy-value :deep(.ant-tooltip-open) {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.device-memo-add-btn {
  align-self: flex-start;
}

.device-info-row {
  margin-bottom: 8px;
  font-size: 12px;
  color: #374151;
  word-break: break-word;
}

.device-info-desc :deep(.ant-descriptions-item-label),
.device-info-desc :deep(.ant-descriptions-item-content) {
  font-size: 14px;
  line-height: 1.55;
}

.device-info-desc :deep(.ant-descriptions-item-label) {
  width: 100px;
  color: #64748b;
}

.device-info-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  flex: 0 0 auto;
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 3;
  min-height: 52px;
  margin: 0;
  padding: 12px;
  border: 1px solid rgba(0,0,0,0.06);
  border-left: 0;
  border-right: 0;
  border-bottom: 0;
  border-radius: 8px;
  background: #f8fafc;
  box-shadow: 0 1px 2px rgba(0,0,0,0.06);
}

.device-info-actions-title {
  flex: 0 0 100%;
  color: #111827;
  font-size: 13px;
  font-weight: 700;
}

.device-link-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.device-link-group-title {
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
}

.device-link-group-title-spaced {
  margin-top: 4px;
  padding-top: 8px;
  border-top: 1px solid rgba(0,0,0,0.06);
}

.device-link-button {
  display: flex !important;
  align-items: center;
  justify-content: space-between !important;
  width: 100%;
  min-height: 30px;
  height: 30px;
  padding: 0 10px !important;
  border-color: rgba(0,0,0,0.06) !important;
  border-radius: 8px !important;
  background: transparent !important;
  color: #334155 !important;
  box-shadow: none !important;
}

.device-link-button:hover,
.device-link-button:focus {
  border-color: rgba(22,119,255,0.18) !important;
  background: rgba(22,119,255,0.06) !important;
  color: #1677ff !important;
}

.device-link-button-main {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.device-link-button-main > span:last-child {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.device-link-button-arrow {
  flex: 0 0 auto;
  color: #94a3b8;
  font-size: 12px;
}

.device-infra-asset-actions {
  margin-top: 12px;
  padding: 12px 0 4px;
  border-top: 1px solid rgba(0,0,0,0.06);
}

.device-infra-asset-action-help {
  margin-bottom: 8px;
  color: #94a3b8;
  font-size: 12px;
  line-height: 1.4;
}

.device-link-asset-change-btn {
  margin-top: 0;
}

.device-link-list-read {
  display: flex;
  flex-direction: column;
  gap: 8px;
  cursor: pointer;
}

.device-inline-link-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.device-inline-link-row {
  display: grid;
  grid-template-columns: minmax(0, 0.75fr) minmax(0, 1fr) 34px;
  gap: 6px;
  align-items: center;
}

.device-info-close-btn {
  color: #64748b !important;
}

.device-info-close-btn:hover {
  color: #334155 !important;
  background: #f1f5f9 !important;
}

.device-memo-wrap {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.device-memo-plain {
  white-space: pre-wrap;
  word-break: break-word;
  color: #334155;
}

.device-host-info-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  white-space: pre-wrap;
  word-break: break-word;
  color: #334155;
}

.device-config-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.device-config-section {
  border-radius: 8px;
  border-color: rgba(0,0,0,0.06);
  box-shadow: 0 1px 2px rgba(0,0,0,0.04);
}

.device-config-section :deep(.ant-card-head) {
  min-height: 38px;
  padding: 0 14px;
  border-bottom-color: rgba(0,0,0,0.06);
}

.device-config-section :deep(.ant-card-head-title) {
  padding: 9px 0;
  color: #111827;
  font-size: 13px;
  font-weight: 700;
}

.device-config-section :deep(.ant-card-body) {
  padding: 14px;
}

.device-config-section :deep(.ant-form-item:last-child) {
  margin-bottom: 0;
}

.device-config-section-form-item :deep(.ant-form-item-label) {
  display: none;
}

.quick-links-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.quick-links-add-btn {
  align-self: flex-start;
}

.device-hardware-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.device-hardware-form-row {
  display: grid;
  grid-template-columns: 128px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
}

.device-hardware-form-row > label {
  color: #475569;
  font-size: 13px;
  font-weight: 600;
}

/* 랙 컨테이너 (가로 정렬) */
.rack-container {
  display: flex;
  gap: 30px;
  align-items: flex-start;
  width: max-content;
  padding-bottom: 0;
}

.rack-zoom-wrapper {
  display: inline-block;
  width: max-content;
}

/* 개별 랙 래퍼 */
.rack-wrapper {
  background: #ffffff;
  border: 1px solid #d9e2ec;
  border-radius: 10px;
  padding: 15px;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.06);
  flex-shrink: 0;
  width: 668px;
  flex: 0 0 668px
}

/* 랙 헤더 */
.rack-header {
  color: #111827;
  margin-bottom: 24px;
  padding: 16px 16px 14px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid #eef2f7;
  border-bottom: 1px solid #1677ff;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.05);
}

.rack-header-top {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  min-height: 42px;
}

.rack-header-title-group {
  display: flex;
  align-items: center;
  align-self: stretch;
  flex: 1 1 auto;
  min-width: 0;
  gap: 10px;
}

.rack-header-title {
  color: #111827;
  font-size: 22px;
  font-weight: 700;
  line-height: 1.2;
  min-width: 0;
  flex: 1 1 auto;
  max-width: none;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rack-header-actions {
  display: flex !important;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

.rack-header-action-group {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.rack-header-action-separator {
  width: 1px;
  height: 26px;
  background: #e5e7eb;
}

.rack-header-action-btn {
  width: 48px !important;
  height: 42px !important;
  padding: 0 !important;
  border-radius: 6px !important;
  display: inline-flex !important;
  align-items: center;
  justify-content: center;
}

.rack-header-action-btn :deep(.anticon) {
  font-size: 20px;
}

.rack-header-action-btn:hover {
  color: #1677ff;
  border-color: #1677ff;
}

.rack-header-meta-row {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  gap: 6px;
  margin-top: 25px;
  color: #6b7280;
  font-size: 14px;
  font-weight: 500;
  min-width: 0;
  overflow: hidden;
}

.rack-header-meta-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
  min-width: 0;
}

.rack-header-meta-item :deep(.anticon) {
  color: #9ca3af;
  font-size: 16px;
}

.rack-header-meta-divider {
  flex: 0 0 auto;
  color: #9ca3af;
  font-weight: 600;
  line-height: 1;
}

.rack-header-meta-location {
  flex: 1 1 auto;
  overflow: hidden;
}

.rack-header-meta-ellipsis {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

/* 랙 본체 가로 정렬용 */
.rack-body {
  display: flex;
  width: 604px;
  margin: 0 auto;
  background: #f1f5f9;
  border-radius: 8px;
  border: 2px solid #cfd8e3;
  overflow: hidden;
}

/* 기존 rack-frame의 중복 테두리와 여백 제거 (rack-body로 옮겼으므로) */
.rack-frame {
  border: none !important;
  border-radius: 0 !important;
  margin: 0 !important;
  background: transparent !important;
}

/* 눈금자 기둥 영역 */
.rack-ruler {
  width: 44px;
  background: #eaf0f7;
  border-right: 1px solid #c5cfdb;
  display: flex;
  flex-direction: column;
}

/*  개별 눈금 텍스트 (정확히 1U = RACK_UNIT_HEIGHT에 맞춤) */
.ruler-number {
  height: 52px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #6b7280;
  font-size: 14px;
  font-family: 'Courier New', monospace;
  font-weight: bold;
  box-sizing: border-box;
  /* 눈금선 느낌을 위해 위아래에 아주 연한 점선 추가 */
  border-bottom: 1px dotted rgba(15, 23, 42, 0.12);
}

/* 맨 위 눈금 상단 선 보정 */
.ruler-number:first-child {
  border-top: 1px dotted rgba(15, 23, 42, 0.12);
}

.ruler-number-selected {
  color: var(--rack-brand-primary);
  background: var(--rack-brand-primary-bg);
  border-left: 1px solid var(--rack-brand-primary);
  border-top: 1px solid var(--rack-brand-primary);
  border-bottom: 1px solid var(--rack-brand-primary);
}

/* 랙 내부 프레임 */
.rack-frame {
  width: 560px;
  background: #f8fbff;
  border: 2px solid #cfd8e3;
  /* 프레임 자체 모서리를 둥글게 */
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  margin: 0 auto;
  /* 내부 장비들이 둥근 모서리를 벗어나지 않도록 잘라냄 */
  overflow: hidden;
  padding: 2px 0;
}

/* 개별 아이템 (장비 또는 여백) */
.rack-item {
  width: 100%;
  position: relative;
  box-sizing: border-box;
  border-bottom: 1px solid #c4cfdd;
  border-radius: 0;
  overflow: hidden; /* 자식 요소의 각진 부분 잘라내기 */
  margin-bottom: 0;
}

.rack-item-hoverable:hover {
  background: rgba(15, 23, 42, 0.025);
}

.rack-item-selected,
.rack-item-selected:hover {
  border: 1px solid var(--rack-brand-primary);
  background: var(--rack-brand-primary-bg);
  z-index: 3;
}

.rack-item-selected::after {
  content: none;
}

.rack-item::after {
  content: "";
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 1px;
  background: #c4cfdd;
  pointer-events: none;
}

.rack-item:last-child {
  border-bottom: none;
  margin-bottom: 0;
}

.rack-item:last-child::after {
  content: none;
}

/* 여백 스타일 */
.gap-content {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  /* 배경을 더 밝은 회색 계열로 변경 */
  background: repeating-linear-gradient(
    45deg,
    #eef2f7,
    #eef2f7 10px,
    #e2e8f0 10px,
    #e2e8f0 20px
  );
  color: #475569;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
  /* 장비와 확실히 구분되도록 안쪽 그림자 제거 혹은 변경 */
  box-shadow: inset 0 0 8px rgba(15, 23, 42, 0.08);
}

/* 여백 호버 시 피드백 강화 */
.gap-content:hover {
  background: #dbe5f2;
  color: #1677ff;
}

/* 장비 내용 래퍼 */
.device-content {
  position: relative;
  height: 100%;
  overflow: hidden;
  cursor: pointer;
  background: #f8fbff !important;
  border: 0;
  box-shadow: none;
}

.device-content-selected,
.device-content-selected:hover {
  background: var(--rack-brand-primary-bg) !important;
}

.device-content-selected .device-name-tag {
  color: var(--rack-brand-primary);
}

.device-content::before {
  content: none;
}

/* 1. 상단 컬러 라인 (기존 SVG 상단 라인 대체) */
.device-top-line {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  opacity: 0.92;
  z-index: 2;
}

/* 2. 질감 패턴 기본 틀 */
.device-pattern {
  position: absolute;
  top: 7px;
  bottom: 7px;
  right: 12px;
  left: 74px;
  z-index: 1;
  opacity: 1;
  border-radius: 6px;
}

.device-pattern :deep(.device-panel-svg) {
  width: 100%;
  height: 100%;
  display: block;
}

.device-pattern :deep(.panel-shell) {
  fill: #f1f5f9;
  stroke: #cfdae6;
  stroke-width: 1.2;
}

.device-pattern :deep(.detail-fill),
.device-pattern :deep(.bay),
.device-pattern :deep(.drive),
.device-pattern :deep(.screen),
.device-pattern :deep(.blade-slot) {
  fill: #bfccd9;
  stroke: #9aa8ba;
  stroke-width: 0.8;
}

.device-pattern :deep(.detail-soft) {
  fill: #d8e1eb;
}

.device-pattern :deep(.thin),
.device-pattern :deep(.vent),
.device-pattern :deep(.line),
.device-pattern :deep(.block),
.device-pattern :deep(.light),
.device-pattern :deep(.detail-mid) {
  fill: #aebccc;
}

.device-pattern :deep(.port),
.device-pattern :deep(.jack),
.device-pattern :deep(.sfp),
.device-pattern :deep(.key),
.device-pattern :deep(.port-shell),
.device-pattern :deep(.outlet-shell),
.device-pattern :deep(.plug-shell) {
  fill: #f8fafc;
  stroke: #64748b;
  stroke-width: 1;
}

.device-pattern :deep(.plug-shell) {
  fill: #f4f7fb;
  stroke: #7f8da0;
  stroke-width: 1.5;
}

.device-pattern :deep(.plug-slot) {
  fill: #66758a;
  stroke: none;
}

.device-pattern :deep(.hole),
.device-pattern :deep(.port-hole),
.device-pattern :deep(.detail-dark) {
  fill: #475569;
  stroke: none;
}

.device-pattern :deep(.dot) {
  fill: #64748b;
}

.device-pattern :deep(.ring),
.device-pattern :deep(.fan),
.device-pattern :deep(.fan-ring) {
  fill: none;
  stroke: #64748b;
  stroke-width: 1.6;
}

.device-pattern :deep(.stroke),
.device-pattern :deep(.fan-line),
.device-pattern :deep(.detail-line) {
  fill: none;
  stroke: #94a3b8;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.device-pattern :deep(.outlet) {
  fill: #f8fafc;
  stroke: #64748b;
  stroke-width: 1.1;
}

.device-pattern :deep(.battery) {
  fill: #64748b;
}

/* 🔲 블랭크 패널: 전체를 덮는 연한 사선 (투명도 1) */
.pattern-blank {
  top: 0; bottom: 0; left: 0; right: 0;
  background-image: repeating-linear-gradient(-45deg, #e2e8f0 0, #e2e8f0 6px, #edf2f7 6px, #edf2f7 12px);
  opacity: 1;
}

/* 3. UPS 전용 정비율 거대 워터마크 */
.ups-watermark {
  display: none;
}

.device-icon-overlay {
  position: absolute;
  left: 23px;
  top: 50%;
  transform: translateY(-50%);
  width: 26px;
  height: 26px;
  opacity: 0.96;
  filter: none;
  pointer-events: none;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 4;
}

.device-icon-overlay::before {
  content: none;
}

/* SVG 내부 아이콘이 영역을 꽉 채우도록 설정 */
.device-icon-overlay :deep(svg) {
  width: 100%;
  height: 100%;
}

/* 장비명 태그 */
.device-name-tag {
  position: absolute;
  top: 50%;
  left: 160px;
  right: 160px;
  transform: translateY(-50%);
  color: #1f2937;
  font-size: 15px;
  font-weight: bold;
  text-shadow: none;
  pointer-events: none;
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 5;
  letter-spacing: -0.2px;
}

.tag-content {
  display: flex;
  align-items: center;
  justify-content: center;
  max-width: 100%;
  min-width: 110px;
  padding: 3px 9px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.1);
  gap: 8px;
}

.tag-text {
  display: block;
  flex: 1 1 auto;
  min-width: 0;
  max-width: none;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tag-badge {
  background: #f5f5f5;
  color: #595959;
  padding: 1px 5px;
  border-radius: 10px;
  flex: 0 0 auto;
  font-size: 10px;
  font-weight: 700;
  border: 1px solid #d9d9d9;
}

/* 1U 장비는 내부 요소의 밀도를 낮춰 상하 여백을 확보합니다. */
.device-content-compact .device-pattern {
  top: 8px;
  bottom: 8px;
}

.device-content-compact .device-icon-overlay {
  width: 24px;
  height: 24px;
}

.device-content-compact .device-name-tag {
  font-size: 14px;
  line-height: 18px;
}

.device-content-compact .tag-content {
  min-width: 100px;
  padding: 2px 8px;
  gap: 6px;
}

.device-content-compact .tag-badge {
  padding: 0 5px;
  font-size: 9px;
  line-height: 14px;
}

/* 액션 버튼 (호버 시에만 표시) */
.device-actions {
  position: absolute !important;
  top: 50%;
  bottom: auto;
  left: 50%;
  transform: translate(-50%, -50%);
  z-index: 5;

  display: flex !important;
  justify-content: center;
  align-items: center;
  gap: 4px !important;
  box-sizing: border-box;
  height: 38px;
  padding: 3px 5px;
  background: rgba(255, 255, 255, 0.96) !important;
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.16);

  opacity: 0;
  pointer-events: none;
  transition: opacity 0.2s ease;
}

.rack-item:hover .device-actions,
.rack-slot:hover .device-actions,
.device-container:hover .device-actions {
  opacity: 1 !important;
  pointer-events: auto !important;
}

.device-actions :deep(.ant-btn.ant-btn-text) {
  width: 30px !important;
  min-width: 30px !important;
  height: 30px !important;
  padding: 0 !important;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #595959 !important;
  background: transparent !important;
  border: 1px solid transparent !important;
  border-radius: 6px !important;
  box-shadow: none !important;
  outline: none !important;
}

.device-actions :deep(.ant-btn.ant-btn-text:hover),
.device-actions :deep(.ant-btn.ant-btn-text:focus),
.device-actions :deep(.ant-btn.ant-btn-text:focus-visible) {
  color: #1677ff !important;
  background: rgba(22, 119, 255, 0.08) !important;
  border-color: transparent !important;
  box-shadow: none !important;
}

.device-actions :deep(.ant-btn.ant-btn-text:active) {
  background: rgba(22, 119, 255, 0.14) !important;
}

.device-actions .ant-btn :deep(.anticon) {
  font-size: 18px !important;
  color: #595959 !important;
  text-shadow: none;
  transition: all 0.2s ease;
}

.device-actions .ant-btn:hover :deep(.anticon) {
  color: #1677ff !important;
}

.device-actions .ant-btn:disabled :deep(.anticon) {
  color: rgba(0, 0, 0, 0.25) !important;
  text-shadow: none;
}

.device-actions :deep(.ant-btn.ant-btn-text.device-more-btn) {
  width: 30px !important;
  height: 30px !important;
  border-radius: 8px !important;
  background: #e6f4ff !important;
}

.device-actions :deep(.ant-btn.ant-btn-dangerous),
.device-actions :deep(.ant-btn.ant-btn-dangerous .anticon) {
  color: #ff4d4f !important;
}

.rack-diagram-root.is-dark .device-actions {
  background: rgba(31, 41, 55, 0.96) !important;
  border-color: rgba(255, 255, 255, 0.14);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.36);
}

.rack-diagram-root.is-dark .device-actions :deep(.ant-btn.ant-btn-text),
.rack-diagram-root.is-dark .device-actions :deep(.ant-btn.ant-btn-text .anticon) {
  color: rgba(255, 255, 255, 0.82) !important;
}

.rack-diagram-root.is-dark .device-actions :deep(.ant-btn.ant-btn-text:hover),
.rack-diagram-root.is-dark .device-actions :deep(.ant-btn.ant-btn-text:focus),
.rack-diagram-root.is-dark .device-actions :deep(.ant-btn.ant-btn-text:focus-visible) {
  color: #69b1ff !important;
  background: rgba(105, 177, 255, 0.14) !important;
}

.rack-diagram-root.is-dark .device-actions :deep(.ant-btn.ant-btn-text.device-more-btn) {
  background: rgba(64, 150, 255, 0.2) !important;
}

.rack-diagram-root.is-dark .device-actions :deep(.ant-btn.ant-btn-dangerous),
.rack-diagram-root.is-dark .device-actions :deep(.ant-btn.ant-btn-dangerous .anticon) {
  color: #ff7875 !important;
}

.device-more-btn :deep(.anticon) {
  font-size: 18px !important;
}

.device-content:hover .device-actions {
  opacity: 1;
}

.host-vm-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px;
}

.host-vm-scroll-area {
  max-height: 52vh;
  overflow-y: auto;
  overflow-x: hidden;
  padding-right: 4px;
}

.host-vm-empty-wrap {
  min-height: 180px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.host-vm-card {
  border: 1px solid #d9e2ec;
  border-radius: 10px;
  background: #f9fbff;
  padding: 8px 12px;
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.host-vm-card:hover {
  border-color: #91caff;
  box-shadow: 0 2px 8px rgba(24, 144, 255, 0.15);
}

.host-vm-card-inactive {
  background: #f3f4f6;
  border-color: #d1d5db;
}

.host-vm-card-inactive .host-vm-icon {
  color: #9ca3af;
}

.host-vm-card-inactive .host-vm-name {
  color: #6b7280;
}

.host-vm-card-inactive .host-vm-meta {
  color: #9ca3af;
}

.host-vm-card-inactive .host-vm-ip {
  color: #9ca3af;
}

.host-vm-icon {
  font-size: 22px;
  color: #3b82f6;
  margin-bottom: 4px;
}

.host-vm-name {
  font-weight: 700;
  color: #1f2937;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.host-vm-meta {
  margin-top: 2px;
  font-size: 12px;
  color: #6b7280;
  line-height: 16px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.host-vm-ip {
  margin-top: 2px;
  font-size: 12px;
  line-height: 16px;
  color: #4b5563;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* dark mode in this project is attached on body */
.rack-diagram-root.is-dark .toolbar-container {
  background: #22282f !important;
  border-color: #3e444c !important;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.35) !important;
}

.rack-diagram-root.is-dark .toolbar-divider {
  background-color: #4a515a !important;
}

.rack-diagram-root.is-dark .toolbar-expand-divider {
  background: #4a515a;
}

.rack-diagram-root.is-dark .toolbar-expand-btn {
  color: rgba(255, 255, 255, 0.72);
  border-color: #4a515a;
  background: #22282f;
}

.rack-diagram-root.is-dark .toolbar-expand-btn:hover,
.rack-diagram-root.is-dark .toolbar-expand-btn:focus {
  color: #69b1ff;
  border-color: #3c89e8;
  background: rgba(105, 177, 255, 0.12);
}

.rack-diagram-root.is-dark .toolbar-zoom {
  border-left-color: #4a515a;
}

.rack-diagram-root.is-dark .rack-canvas {
  scrollbar-color: var(--ui-scroll-thumb) var(--ui-scroll-track);
  background: radial-gradient(circle, #232a33 0%, #1b2129 100%);
}

.rack-diagram-root.is-dark .rack-list-view {
  background: #151b24;
}

.rack-diagram-root.is-dark .rack-list-card {
  background: #1f2732 !important;
  border-color: #3a4654 !important;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.22);
}

.rack-diagram-root.is-dark :deep(.rack-list-card.ant-card) {
  background: #1f2732 !important;
  border-color: #3a4654 !important;
}

.rack-diagram-root.is-dark :deep(.rack-list-card.ant-card > .ant-card-body) {
  background: transparent !important;
}

.rack-diagram-root.is-dark .rack-list-card:hover {
  border-color: #4c93ff !important;
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.34);
}

.rack-diagram-root.is-dark .rack-list-card :deep(.ant-card-body) {
  color: rgba(255, 255, 255, 0.86);
}

.rack-diagram-root.is-dark .rack-list-summary-card {
  background: #1f2732 !important;
  border-color: #3a4654 !important;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.24);
}

.rack-diagram-root.is-dark :deep(.rack-list-summary-card.ant-card) {
  background: #1f2732 !important;
  border-color: #3a4654 !important;
}

.rack-diagram-root.is-dark :deep(.rack-list-summary-card.ant-card > .ant-card-body) {
  background: transparent !important;
}

.rack-diagram-root.is-dark .rack-list-summary-title,
.rack-diagram-root.is-dark .rack-list-summary-label,
.rack-diagram-root.is-dark .rack-list-summary-value {
  color: rgba(255, 255, 255, 0.92);
}

.rack-diagram-root.is-dark .rack-list-summary-desc,
.rack-diagram-root.is-dark .rack-list-summary-label {
  color: rgba(255, 255, 255, 0.62);
}

.rack-diagram-root.is-dark .rack-list-summary-title {
  border-right-color: #334155;
}

.rack-diagram-root.is-dark .rack-list-summary-icon {
  background: rgba(22, 119, 255, 0.20);
  color: #8cc8ff;
}

.rack-diagram-root.is-dark .rack-list-summary-progress :deep(.ant-progress-inner),
.rack-diagram-root.is-dark .rack-list-progress :deep(.ant-progress-inner),
.rack-diagram-root.is-dark .rack-list-row-progress :deep(.ant-progress-inner) {
  background-color: #334155;
}

.rack-diagram-root.is-dark .rack-list-view-switch :deep(.ant-radio-button-wrapper) {
  background: #1f2732;
  border-color: #3a4654;
  color: rgba(255, 255, 255, 0.72);
}

.rack-diagram-root.is-dark .rack-list-view-switch :deep(.ant-radio-button-wrapper:not(.ant-radio-button-wrapper-checked):hover) {
  color: #8cc8ff;
  border-color: #4c93ff;
}

.rack-diagram-root.is-dark .rack-list-view-switch :deep(.ant-radio-button-wrapper-checked) {
  background: #1677ff;
  border-color: #1677ff;
  color: #fff;
}

.rack-diagram-root.is-dark .rack-list-summary-metric + .rack-list-summary-metric {
  border-left-color: #334155;
}

.rack-diagram-root.is-dark .rack-list-card--matched {
  border-color: #4c93ff;
  box-shadow: 0 0 0 1px rgba(76, 147, 255, 0.32) inset;
}

.rack-diagram-root.is-dark .rack-list-more-btn {
  color: rgba(255, 255, 255, 0.72) !important;
}

.rack-diagram-root.is-dark .rack-list-more-btn:hover {
  color: rgba(255, 255, 255, 0.9) !important;
  background: #273244 !important;
}

.rack-diagram-root.is-dark .rack-list-card-title {
  color: rgba(255, 255, 255, 0.94);
}

.rack-diagram-root.is-dark .rack-list-card-rack-icon {
  color: rgba(255, 255, 255, 0.82);
}

.rack-diagram-root.is-dark .rack-list-card-stat-label,
.rack-diagram-root.is-dark .rack-list-card-footer,
.rack-diagram-root.is-dark .rack-list-card-usage-detail,
.rack-diagram-root.is-dark .rack-list-card-extra {
  color: rgba(255, 255, 255, 0.72);
}

.rack-diagram-root.is-dark .rack-list-card-usage {
  color: rgba(255, 255, 255, 0.82);
}

.rack-diagram-root.is-dark .rack-list-card-stat strong {
  color: rgba(255, 255, 255, 0.92);
}

.rack-diagram-root.is-dark .rack-list-card-stats,
.rack-diagram-root.is-dark .rack-list-card-footer,
.rack-diagram-root.is-dark .rack-list-card-stat + .rack-list-card-stat {
  border-color: #334155;
}

.rack-diagram-root.is-dark .rack-list-add-card {
  background: rgba(31, 39, 50, 0.72);
  border-color: #475569;
  color: rgba(255, 255, 255, 0.86);
}

.rack-diagram-root.is-dark .rack-list-add-card:hover {
  background: rgba(22, 119, 255, 0.10);
  border-color: #4c93ff;
  color: #8cc8ff;
}

.rack-diagram-root.is-dark .rack-list-add-icon {
  background: rgba(22, 119, 255, 0.16);
  color: #8cc8ff;
}

.rack-diagram-root.is-dark .rack-list-row {
  background: #1f2732 !important;
  border-color: #3a4654 !important;
  box-shadow: 0 3px 10px rgba(0, 0, 0, 0.14);
}

.rack-diagram-root.is-dark .rack-list-row:hover {
  border-color: #4c93ff;
}

.rack-diagram-root.is-dark .rack-list-row--matched {
  border-color: #4c93ff;
  box-shadow: 0 0 0 1px rgba(76, 147, 255, 0.32) inset;
}

.rack-diagram-root.is-dark .rack-list-row-title,
.rack-diagram-root.is-dark .rack-list-row-usage strong,
.rack-diagram-root.is-dark .rack-list-row-stats strong {
  color: rgba(255, 255, 255, 0.94);
}

.rack-diagram-root.is-dark .rack-list-row-icon {
  color: rgba(255, 255, 255, 0.82);
}

.rack-diagram-root.is-dark .rack-list-row-meta,
.rack-diagram-root.is-dark .rack-list-row-label,
.rack-diagram-root.is-dark .rack-list-row-used,
.rack-diagram-root.is-dark .rack-list-row-stats {
  color: rgba(255, 255, 255, 0.72);
}

.rack-diagram-root.is-dark .rack-list-add-row {
  background: rgba(31, 39, 50, 0.7);
  border-color: #475569;
  color: rgba(255, 255, 255, 0.86);
}

.rack-diagram-root.is-dark .rack-list-add-row:hover {
  background: rgba(22, 119, 255, 0.10);
  border-color: #4c93ff;
  color: #8cc8ff;
}

.rack-diagram-root.is-dark .device-info-row {
  color: rgba(255, 255, 255, 0.78);
}

.rack-diagram-root.is-dark .device-info-desc :deep(.ant-descriptions-item-label) {
  color: rgba(255, 255, 255, 0.68);
}

.rack-diagram-root.is-dark .rack-side-pane-card :deep(.ant-card-head) {
  background: #1f2732;
  border-bottom-color: rgba(255, 255, 255, 0.08);
}

.rack-diagram-root.is-dark .rack-side-pane-card :deep(.ant-card-head-title) {
  color: rgba(255, 255, 255, 0.92);
}

.rack-diagram-root.is-dark .rack-side-pane-card {
  background: #1f2732;
  border-color: #3a4654;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.35);
}

.rack-diagram-root.is-dark .rack-side-pane-card :deep(.ant-card-body) {
  background: #151b24;
}

.rack-diagram-root.is-dark .rack-side-pane {
  --device-scrollbar-thumb: var(--ui-scroll-thumb);
  --device-scrollbar-thumb-hover: var(--ui-scroll-thumb-hover);
}

.rack-diagram-root.is-dark .device-summary-header,
.rack-diagram-root.is-dark .device-info-section,
.rack-diagram-root.is-dark .device-info-actions,
.rack-diagram-root.is-dark .device-memo-table,
.rack-diagram-root.is-dark .device-free-memo-read,
.rack-diagram-root.is-dark .device-linked-asset-card {
  background: #202938;
  border-color: rgba(255, 255, 255, 0.08);
}

.rack-diagram-root.is-dark .device-summary-title,
.rack-diagram-root.is-dark .device-info-section-title {
  color: rgba(255, 255, 255, 0.92);
}

.rack-diagram-root.is-dark .device-summary-subtitle {
  color: rgba(255, 255, 255, 0.66);
}

.rack-diagram-root.is-dark .device-status-inline {
  color: rgba(255, 255, 255, 0.72);
}

.rack-diagram-root.is-dark .device-info-tabs :deep(.ant-tabs-nav) {
  background: transparent;
}

.rack-diagram-root.is-dark .device-info-tabs :deep(.ant-tabs-tab) {
  color: rgba(255, 255, 255, 0.72);
}

.rack-diagram-root.is-dark .device-info-tabs :deep(.ant-tabs-tab:hover) {
  color: #8cc8ff;
}

.rack-diagram-root.is-dark .device-info-tabs :deep(.ant-tabs-tab.ant-tabs-tab-active .ant-tabs-tab-btn) {
  color: #4c93ff;
}

.rack-diagram-root.is-dark .device-info-tabs :deep(.ant-tabs-ink-bar) {
  background: #4c93ff;
}

.rack-diagram-root.is-dark .device-info-tabs :deep(.ant-tabs-content-holder),
.rack-diagram-root.is-dark .device-info-tabs :deep(.ant-tabs-content),
.rack-diagram-root.is-dark .device-info-tabs :deep(.ant-tabs-tabpane) {
  background: #151b24;
}

.rack-diagram-root.is-dark .rack-side-pane :deep(.ant-input),
.rack-diagram-root.is-dark .rack-side-pane :deep(.ant-input-number),
.rack-diagram-root.is-dark .rack-side-pane :deep(.ant-select-selector),
.rack-diagram-root.is-dark .rack-side-pane :deep(.ant-picker),
.rack-diagram-root.is-dark .rack-side-pane :deep(textarea.ant-input) {
  background: #17202b !important;
  border-color: #465366 !important;
  color: rgba(255, 255, 255, 0.86) !important;
}

.rack-diagram-root.is-dark .rack-side-pane :deep(.ant-input::placeholder),
.rack-diagram-root.is-dark .rack-side-pane :deep(textarea.ant-input::placeholder) {
  color: rgba(255, 255, 255, 0.38) !important;
}

.rack-diagram-root.is-dark .rack-side-pane :deep(.ant-empty-description) {
  color: rgba(255, 255, 255, 0.58);
}

.rack-diagram-root.is-dark .device-section-edit-btn {
  color: rgba(255, 255, 255, 0.62) !important;
}

.rack-diagram-root.is-dark .device-section-edit-btn:hover {
  color: #8cc8ff !important;
  background: rgba(76, 147, 255, 0.12) !important;
}

.rack-diagram-root.is-dark .device-memo-table-head,
.rack-diagram-root.is-dark .device-inline-row > label,
.rack-diagram-root.is-dark .device-info-table-row > div:first-child {
  background: #253144;
  color: rgba(255, 255, 255, 0.78);
}

.rack-diagram-root.is-dark .device-memo-cell-text,
.rack-diagram-root.is-dark .device-free-memo-read,
.rack-diagram-root.is-dark .device-linked-asset-name,
.rack-diagram-root.is-dark .device-readonly-value,
.rack-diagram-root.is-dark .device-info-table-row > div {
  color: rgba(255, 255, 255, 0.82);
}

.rack-diagram-root.is-dark .device-spec-unit {
  background: #263244;
  border-color: #465366;
  color: rgba(255, 255, 255, 0.72);
}

.rack-diagram-root.is-dark .device-linked-asset-ip {
  color: rgba(255, 255, 255, 0.62);
}

.rack-diagram-root.is-dark .device-summary-badge,
.rack-diagram-root.is-dark .tag-badge {
  background: #2b3544;
  color: rgba(255, 255, 255, 0.78);
  border-color: #465366;
}

.rack-diagram-root.is-dark .device-memo-table-head > div,
.rack-diagram-root.is-dark .device-memo-table-row > *,
.rack-diagram-root.is-dark .device-memo-table-row,
.rack-diagram-root.is-dark .device-inline-form,
.rack-diagram-root.is-dark .device-inline-row,
.rack-diagram-root.is-dark .device-info-table,
.rack-diagram-root.is-dark .device-info-table-row,
.rack-diagram-root.is-dark .device-info-tabs :deep(.ant-tabs-nav),
.rack-diagram-root.is-dark .device-link-group-title-spaced {
  border-color: rgba(255, 255, 255, 0.08);
}

.rack-diagram-root.is-dark .device-link-group-title {
  color: rgba(255, 255, 255, 0.66);
}

.rack-diagram-root.is-dark .device-link-button {
  border-color: rgba(255, 255, 255, 0.08) !important;
  background: rgba(15, 23, 42, 0.14) !important;
  color: rgba(255, 255, 255, 0.80) !important;
}

.rack-diagram-root.is-dark .device-link-button:hover,
.rack-diagram-root.is-dark .device-link-button:focus {
  border-color: rgba(76, 147, 255, 0.34) !important;
  background: rgba(76, 147, 255, 0.14) !important;
  color: #8cc8ff !important;
}

.rack-diagram-root.is-dark .device-link-button-arrow {
  color: rgba(255, 255, 255, 0.66);
}

.rack-diagram-root.is-dark .device-link-button:hover .device-link-button-arrow,
.rack-diagram-root.is-dark .device-link-button:focus .device-link-button-arrow {
  color: #8cc8ff;
}

.rack-diagram-root.is-dark .device-info-close-btn {
  color: rgba(255, 255, 255, 0.7) !important;
}

.rack-diagram-root.is-dark .device-info-close-btn:hover {
  color: rgba(255, 255, 255, 0.92) !important;
  background: #273244 !important;
}

.rack-diagram-root.is-dark .rack-wrapper {
  background: #1f2732;
  border-color: #3a4654;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.26);
}

.rack-diagram-root.is-dark .rack-header {
  background: #202938;
  border-color: #3a4654;
  border-bottom-color: #4c93ff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.22);
}

.rack-diagram-root.is-dark .rack-header-title {
  color: rgba(255, 255, 255, 0.94);
}

.rack-diagram-root.is-dark .rack-header-meta-row,
.rack-diagram-root.is-dark .rack-header-meta-divider {
  color: rgba(255, 255, 255, 0.64);
}

.rack-diagram-root.is-dark .rack-header-meta-item :deep(.anticon) {
  color: rgba(255, 255, 255, 0.52);
}

.rack-diagram-root.is-dark .rack-header-action-separator {
  background: #3a4654;
}

.rack-diagram-root.is-dark .rack-header-action-btn {
  background: #1b2430;
  border-color: #465366;
  color: rgba(255, 255, 255, 0.82);
}

.rack-diagram-root.is-dark .rack-header-action-btn:hover {
  color: #8cc8ff;
  border-color: #4c93ff;
}

.rack-diagram-root.is-dark .rack-body {
  background: #1b2430;
  border-color: #3a4654;
}

.rack-diagram-root.is-dark .rack-ruler {
  background: #202938;
  border-right-color: #3a4654;
}

.rack-diagram-root.is-dark .ruler-number {
  color: rgba(255, 255, 255, 0.58);
  border-bottom-color: rgba(255, 255, 255, 0.08);
}

.rack-diagram-root.is-dark .ruler-number:first-child {
  border-top-color: rgba(255, 255, 255, 0.08);
}

.rack-diagram-root.is-dark .ruler-number-selected {
  color: var(--rack-brand-primary);
  background: var(--rack-brand-primary-bg-strong);
  border-color: var(--rack-brand-primary);
}

.rack-diagram-root.is-dark .rack-frame {
  background: #17202b !important;
  border-color: #3a4654 !important;
}

.rack-diagram-root.is-dark .rack-item {
  background: #17202b;
  border-bottom-color: #334155;
}

.rack-diagram-root.is-dark .rack-item::after {
  background: #334155;
}

.rack-diagram-root.is-dark .rack-item:not(.rack-item-selected) {
  box-shadow: inset 44px 0 0 rgba(31, 41, 55, 0.42);
}

.rack-diagram-root.is-dark .rack-item-hoverable:hover {
  background: rgba(76, 147, 255, 0.08);
}

.rack-diagram-root.is-dark .rack-item-selected,
.rack-diagram-root.is-dark .rack-item-selected:hover {
  border-color: var(--rack-brand-primary);
  background: var(--rack-brand-primary-bg-strong);
  box-shadow: inset 44px 0 0 var(--rack-brand-primary-bg-strong);
}

.rack-diagram-root.is-dark .device-content {
  background: linear-gradient(90deg, #202938 0, #202938 74px, #17202b 74px, #17202b 100%) !important;
}

.rack-diagram-root.is-dark .device-content-selected,
.rack-diagram-root.is-dark .device-content-selected:hover {
  background: linear-gradient(90deg, rgba(24, 144, 255, 0.22) 0, rgba(24, 144, 255, 0.22) 74px, rgba(24, 144, 255, 0.12) 74px, rgba(24, 144, 255, 0.12) 100%) !important;
}

.rack-diagram-root.is-dark .gap-content {
  background: repeating-linear-gradient(
    45deg,
    #17202b,
    #17202b 10px,
    #1f2a38 10px,
    #1f2a38 20px
  ) !important;
  color: rgba(255, 255, 255, 0.68);
  box-shadow: inset 0 0 8px rgba(0, 0, 0, 0.24);
}

.rack-diagram-root.is-dark .gap-content:hover {
  background: #202938 !important;
  color: #8cc8ff;
}

.rack-diagram-root.is-dark .pattern-blank {
  background-image: repeating-linear-gradient(-45deg, #1f2a38 0, #1f2a38 6px, #17202b 6px, #17202b 12px);
}

.rack-diagram-root.is-dark .device-top-line {
  opacity: 0.95;
}

.rack-diagram-root.is-dark .device-icon-overlay {
  filter: drop-shadow(0 1px 1px rgba(0, 0, 0, 0.5));
}

.rack-diagram-root.is-dark .device-pattern :deep(.panel-shell) {
  fill: #263241;
  stroke: #4a5a6d;
}

.rack-diagram-root.is-dark .device-pattern :deep(.detail-fill),
.rack-diagram-root.is-dark .device-pattern :deep(.bay),
.rack-diagram-root.is-dark .device-pattern :deep(.drive),
.rack-diagram-root.is-dark .device-pattern :deep(.screen),
.rack-diagram-root.is-dark .device-pattern :deep(.blade-slot) {
  fill: #607086;
  stroke: #7f8da0;
}

.rack-diagram-root.is-dark .device-pattern :deep(.detail-soft) {
  fill: #334155;
}

.rack-diagram-root.is-dark .device-pattern :deep(.thin),
.rack-diagram-root.is-dark .device-pattern :deep(.vent),
.rack-diagram-root.is-dark .device-pattern :deep(.line),
.rack-diagram-root.is-dark .device-pattern :deep(.block),
.rack-diagram-root.is-dark .device-pattern :deep(.light),
.rack-diagram-root.is-dark .device-pattern :deep(.detail-mid) {
  fill: #74849a;
}

.rack-diagram-root.is-dark .device-pattern :deep(.port),
.rack-diagram-root.is-dark .device-pattern :deep(.jack),
.rack-diagram-root.is-dark .device-pattern :deep(.sfp),
.rack-diagram-root.is-dark .device-pattern :deep(.key),
.rack-diagram-root.is-dark .device-pattern :deep(.port-shell),
.rack-diagram-root.is-dark .device-pattern :deep(.outlet-shell),
.rack-diagram-root.is-dark .device-pattern :deep(.plug-shell),
.rack-diagram-root.is-dark .device-pattern :deep(.outlet) {
  fill: #202938;
  stroke: #8b9bb0;
}

.rack-diagram-root.is-dark .device-pattern :deep(.hole),
.rack-diagram-root.is-dark .device-pattern :deep(.port-hole),
.rack-diagram-root.is-dark .device-pattern :deep(.detail-dark),
.rack-diagram-root.is-dark .device-pattern :deep(.battery) {
  fill: #cbd5e1;
}

.rack-diagram-root.is-dark .device-pattern :deep(.dot),
.rack-diagram-root.is-dark .device-pattern :deep(.plug-slot) {
  fill: #cbd5e1;
}

.rack-diagram-root.is-dark .device-pattern :deep(.ring),
.rack-diagram-root.is-dark .device-pattern :deep(.fan),
.rack-diagram-root.is-dark .device-pattern :deep(.fan-ring),
.rack-diagram-root.is-dark .device-pattern :deep(.stroke),
.rack-diagram-root.is-dark .device-pattern :deep(.fan-line),
.rack-diagram-root.is-dark .device-pattern :deep(.detail-line) {
  stroke: #94a3b8;
}

.rack-diagram-root.is-dark .tag-text {
  color: rgba(255, 255, 255, 0.92);
}

.rack-diagram-root.is-dark .tag-content {
  background: rgba(15, 23, 42, 0.72);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.35);
}

@media (min-width: 981px) and (max-width: 1640px) {
  .rack-list-summary-card :deep(.ant-card-body) {
    padding: 18px 24px;
  }

  .rack-list-summary-metrics {
    display: grid;
    grid-template-columns: 100px repeat(2, minmax(180px, 1fr));
    align-items: center;
    gap: 16px 20px;
  }

  .rack-list-summary-title {
    grid-row: 1 / span 3;
    align-self: stretch;
    display: inline-flex;
    align-items: center;
    min-width: 0;
    padding-right: 18px;
    margin-right: 0;
  }

  .rack-list-summary-metric {
    min-width: 0;
    width: 100%;
    padding: 0;
    border-left: 0 !important;
  }

  .rack-list-summary-metric:nth-of-type(4),
  .rack-list-summary-metric:nth-of-type(5) {
    min-width: 0;
  }

  .rack-list-summary-metric:nth-of-type(6) {
    grid-column: 2 / 3;
  }

  .rack-list-card-footer {
    gap: 10px;
  }

  .rack-list-card-location {
    max-width: 180px;
  }

  .rack-list-row {
    grid-template-columns: minmax(0, 1fr) 56px;
    align-items: start;
    gap: 12px 16px;
  }

  .rack-list-row-main {
    grid-column: 1 / 2;
    min-width: 0;
  }

  .rack-list-row-actions {
    grid-column: 2 / 3;
    grid-row: 1 / 2;
    align-self: start;
    min-width: 56px;
  }

  .rack-list-row-usage,
  .rack-list-row-stats {
    grid-column: 1 / 3;
  }

  .rack-list-row-usage {
    grid-template-columns: auto auto minmax(120px, 1fr) auto;
    max-width: 100%;
  }

  .rack-list-row-stats {
    flex-direction: row;
    gap: 18px;
  }

  .rack-list-row-title {
    max-width: min(420px, 100%);
  }

  .rack-list-row-location {
    max-width: 360px;
  }
}

@media (max-width: 980px) {
  .rack-list-grid {
    grid-template-columns: 1fr;
  }

  .rack-list-summary-metrics {
    display: flex;
    flex-wrap: wrap;
    align-items: flex-start;
    row-gap: 8px;
  }

  .rack-list-summary-title {
    grid-row: auto;
    flex: 1 0 100%;
    border-right: 0;
    padding-right: 0;
    margin-right: 0;
  }

  .rack-list-summary-metric {
    padding: 0 14px 0 0;
  }

  .rack-list-summary-metric + .rack-list-summary-metric {
    border-left: 0;
  }

  .rack-list-card-progress-row {
    grid-template-columns: 1fr;
    gap: 6px;
  }

  .rack-list-card-footer {
    flex-wrap: wrap;
    gap: 8px 14px;
  }

  .rack-list-row {
    grid-template-columns: minmax(0, 1fr) 42px;
    gap: 12px 14px;
  }

  .rack-list-row-main,
  .rack-list-row-usage,
  .rack-list-row-stats {
    grid-column: 1 / 2;
  }

  .rack-list-row-actions {
    grid-column: 2 / 3;
    grid-row: 1 / 2;
    align-self: start;
  }

  .rack-list-row-title {
    max-width: 220px;
  }

  .rack-list-row-stats {
    flex-direction: row;
    gap: 18px;
  }

  .rack-side-pane-slot {
    width: 280px;
    flex: 0 0 280px;
  }
}

.rack-diagram-root.is-dark .host-vm-card {
  background: #1f2732;
  border-color: #3a4654;
}

.rack-diagram-root.is-dark .host-vm-name {
  color: rgba(255, 255, 255, 0.9);
}

.rack-diagram-root.is-dark .host-vm-meta {
  color: rgba(255, 255, 255, 0.65);
}

.rack-diagram-root.is-dark .host-vm-ip {
  color: rgba(255, 255, 255, 0.72);
}

.rack-diagram-root.is-dark .toolbar-container :deep(.ant-input),
.rack-diagram-root.is-dark .toolbar-container :deep(.ant-input-number),
.rack-diagram-root.is-dark .toolbar-container :deep(.ant-input-number-input),
.rack-diagram-root.is-dark .toolbar-container :deep(.ant-input-search .ant-input),
.rack-diagram-root.is-dark .toolbar-container :deep(.ant-input-group-addon),
.rack-diagram-root.is-dark .toolbar-container :deep(.ant-slider-rail) {
  background: #1a212b !important;
  border-color: #4a515a !important;
  color: rgba(255, 255, 255, 0.85) !important;
}

.rack-diagram-root.is-dark .toolbar-container :deep(.ant-input::placeholder),
.rack-diagram-root.is-dark .toolbar-container :deep(.ant-input-number-input::placeholder) {
  color: rgba(255, 255, 255, 0.45) !important;
}

.rack-diagram-root.is-dark .toolbar-container :deep(.ant-input-search-button),
.rack-diagram-root.is-dark .toolbar-container :deep(.ant-slider-track) {
  background: #2a8be7 !important;
  border-color: #2a8be7 !important;
}

.rack-diagram-root.is-dark .toolbar-container :deep(.ant-slider-handle) {
  border-color: #69c0ff !important;
}

/* ant-space-item wrappers can override inherited color */
.rack-diagram-root.is-dark .toolbar-container :deep(.ant-space-item) {
  color: rgba(255, 255, 255, 0.88) !important;
}

.rack-diagram-root.is-dark .toolbar-container :deep(.ant-space-item .ant-btn) {
  background: #1a212b !important;
  border-color: #4a515a !important;
  color: rgba(255, 255, 255, 0.88) !important;
}

.rack-diagram-root.is-dark .toolbar-container :deep(.ant-space-item .ant-btn > span),
.rack-diagram-root.is-dark .toolbar-container :deep(.ant-space-item .ant-btn .anticon),
.rack-diagram-root.is-dark .toolbar-container :deep(.ant-space-item .ant-btn .anticon svg) {
  color: rgba(255, 255, 255, 0.88) !important;
  fill: currentColor !important;
  -webkit-text-fill-color: rgba(255, 255, 255, 0.88) !important;
}

.rack-diagram-root.is-dark .toolbar-container :deep(.ant-space-item .ant-btn-primary) {
  background: #1677ff !important;
  border-color: #1677ff !important;
}

.rack-diagram-root.is-dark .toolbar-container :deep(.ant-space-item .ant-btn-primary > span),
.rack-diagram-root.is-dark .toolbar-container :deep(.ant-space-item .ant-btn-primary .anticon),
.rack-diagram-root.is-dark .toolbar-container :deep(.ant-space-item .ant-btn-primary .anticon svg) {
  color: #ffffff !important;
  fill: currentColor !important;
  -webkit-text-fill-color: #ffffff !important;
}

/* Keep disabled primary actions visibly inactive in the dark theme. */
.rack-diagram-root.is-dark .toolbar-container :deep(.ant-space-item .ant-btn-primary[disabled]),
.rack-diagram-root.is-dark .toolbar-container :deep(.ant-space-item .ant-btn-primary.ant-btn-disabled),
.rack-diagram-root.is-dark .toolbar-container :deep(.ant-space-item .ant-btn-primary[disabled]:hover),
.rack-diagram-root.is-dark .toolbar-container :deep(.ant-space-item .ant-btn-primary.ant-btn-disabled:hover) {
  background: #2a3038 !important;
  border-color: #434a54 !important;
  color: rgba(255, 255, 255, 0.35) !important;
  box-shadow: none !important;
}

.rack-diagram-root.is-dark .toolbar-container :deep(.ant-space-item .ant-btn-primary[disabled] > span),
.rack-diagram-root.is-dark .toolbar-container :deep(.ant-space-item .ant-btn-primary[disabled] .anticon),
.rack-diagram-root.is-dark .toolbar-container :deep(.ant-space-item .ant-btn-primary[disabled] .anticon svg),
.rack-diagram-root.is-dark .toolbar-container :deep(.ant-space-item .ant-btn-primary.ant-btn-disabled > span),
.rack-diagram-root.is-dark .toolbar-container :deep(.ant-space-item .ant-btn-primary.ant-btn-disabled .anticon),
.rack-diagram-root.is-dark .toolbar-container :deep(.ant-space-item .ant-btn-primary.ant-btn-disabled .anticon svg) {
  color: rgba(255, 255, 255, 0.35) !important;
  fill: currentColor !important;
  -webkit-text-fill-color: rgba(255, 255, 255, 0.35) !important;
}
</style>
