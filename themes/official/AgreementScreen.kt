package com.xingshi.ai.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.xingshi.ai.themes.official.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingshi.ai.theme.framework.themeText

internal val agreementPreface = "请在使用星识前阅读本协议。本协议适用于官方发布的星识应用及配套服务。开源代码的使用另遵循项目许可证，第三方插件及服务还可能适用各自的使用条款。"

internal val sec1Title = "一、关于星识"
internal val sec1Body = """
星识是一款面向日常生活的个人智能助理应用，支持日常交流、信息整理和生活事务辅助，并可通过选装插件扩展能力。

您可以将它作为助理、朋友或搭档。星识不预设固定的相处关系，也不承诺始终认同您的观点。它可能表达不同意见、修正先前的判断，或在理解不足时向您询问。

星识提供的交流与辅助功能，不替代医疗诊断、心理咨询、法律服务等专业服务。
""".trim()

internal val sec2Title = "二、使用资格与安全管理"
internal val sec2Body = """
1. 您须年满18周岁方可使用星识。
2. 应用内置安全机制会对输入内容进行安全检查，拦截命中规则的违规内容。检查失败或尚未完成时，本次内容不会继续发送。
3. 对于频繁触发违规拦截、反复尝试绕过检查、恶意滥用或破坏服务的行为，开发者保留限制使用、暂停服务或封禁相关账号、设备的权利。
4. 为识别恶意使用并执行上述限制，应用会使用设备标识符，例如 ANDROID_ID。该标识用于反滥用与安全管理，不用于广告营销，也不会出售给第三方。
5. 如您认为输入拦截或使用限制存在误判，可以通过项目公布的联系方式反馈。
""".trim()

internal val sec3Title = "三、AI内容说明"
internal val sec3Body = """
1. 星识的回复及部分分析内容由人工智能模型生成，可能存在事实错误、遗漏、误解或不合适的表达。
2. AI的回复不代表开发者的观点、立场或专业建议。涉及健康、财产、安全及其他重要事项时，请核实相关信息，不要仅依据AI回复作出决定。
3. AI表达的回忆、判断或行动描述，不应直接视为已经发生的事实或已经完成的操作。您可以指出错误并纠正它的理解。
4. 输入安全检查不能保证识别所有不当内容，也不能保证模型回复始终准确或适宜。如发现问题，请停止相关操作并反馈。
""".trim()

internal val sec4Title = "四、可接受使用政策（AUP）"
internal val sec4Body = """
您不得利用星识：

1. 生成、传播色情或淫秽内容。
2. 实施、策划、指导或协助违法犯罪活动。
3. 宣传恐怖主义、进行相关招募，或提供实施协助。
4. 侵犯他人的隐私、知识产权、名誉及其他合法权益。
5. 窃取账号或凭据、攻击系统、恶意消耗资源，或干扰其他人的正常使用。
6. 绕过输入检查、账号限制或其他必要的安全措施。

正常的知识讨论、新闻交流、举报、求助及合法用途，不应仅因出现相关词汇而被认定为违规。

本政策适用于官方应用及配套服务的使用，不以此增加或改变开源代码许可证中的限制。
""".trim()

internal val sec5Title = "五、隐私与数据处理"
internal val sec5Body = """
1. 本地聊天记录。 原始聊天记录保留在您的设备本地，供您查阅、导出和保存。请妥善保管设备及自行导出的文件。
2. 云端长期交互。 为支持长期交流，必要角色资料与摘要会发送至星识部署在阿里云上的服务进行处理。云端会按角色保存交流摘要、重要实体/关联等记忆及其他维持长期交互所必要的策略算法信息。生成回复或进行必要分析时，相关内容也会由您填写的对应云端模型供应商处理。如您拒绝星识并非纯本地应用，请停止使用。
3. 以角色为信息边界。 各角色的长期交互信息独立管理，星识不另行汇总建立跨角色的全局用户画像。用于账号管理和反滥用的必要账号信息、设备标识，仅用于相应功能。
4. 角色数据用途。 星识不会将您的角色资料或长期交互信息出售或转卖，也不会将这些数据提供给第三方用于训练模型；相关算法策略只用于维持您与角色的长期交互。
5. 删除角色。 删除角色并完成确认后，该角色在星识云端保存的全部交互数据及关联策略信息将被永久删除。不设回收站或缓冲期，不保留用于恢复该角色的云端副本，操作无法撤销，请谨慎确认。
6. 注销账号。 注销账号后，该账号下所有角色的云端交互数据及关联策略信息将全部永久清空，不设恢复期，也不存在角色数据恢复途径。请在注销前自行保存需要保留的内容。
7. 第三方服务。 您自行配置的模型服务及安装的插件，可能具有独立的数据处理规则。使用前请阅读相应说明，确认其数据用途和保存规则。星识对自身数据处理作出的承诺，不代替第三方服务商或插件作者的承诺。
""".trim()

internal val sec6Title = "六、模型服务与插件"
internal val sec6Body = """
1. 星识目前通过云端模型API提供模型能力，功能可能受到网络、模型服务可用性、调用额度及服务商政策的影响。
2. 聊天以外的扩展能力可通过插件选装。插件由相应作者实现和维护，具体功能、兼容性及使用要求以插件说明为准。
3. 插件可能联网、调用模型或处理应用内数据。请选择可信作者的插件，并在安装、使用前了解其用途。星识不承诺插件与应用本地数据完全隔离。
4. 模型调用及外部服务可能产生费用，具体计费以您使用的服务及配置为准。
5. 插件或第三方服务的故障，可能影响对应功能。安装或市场上架不代表对其准确性、持续可用性或适合所有用途作出保证。
""".trim()

internal val sec7Title = "七、知识产权与开源授权"
internal val sec7Body = """
星识前端基于 LianYu-app 等开源成果开发，保留原作者、贡献者及第三方依赖应有的署名和许可声明。

开源代码的使用、修改及分发，以项目所附的 Apache License 2.0、NOTICE及相关依赖许可证为准。本协议不收回许可证已授予的权利。应用名称、标识及其他素材的使用，依其各自适用的权利和授权确定。

您对自行提供内容依法享有的权利，不因使用星识而转移给开发者。AI生成内容的权利归属及使用条件，依适用法律和相应模型服务条款确定。
""".trim()

internal val sec8Title = "八、服务可用性与责任边界"
internal val sec8Body = """
星识按现有功能和实际可用状态提供，不保证服务始终无中断、无错误，或满足所有个人需求。功能可能因维护、网络故障、模型服务变化及其他原因受到影响。

请自行核实重要信息、妥善保管账号及API凭据，并备份需要长期保存的本地内容。因用户自身不当操作、凭据泄露或违反使用规则产生的问题，由相关责任方依法承担相应责任。

开发者依法承担自身应承担的责任。本协议不排除或限制依法不得排除、限制的责任。
""".trim()

internal val sec9Title = "九、协议变更与终止使用"
internal val sec9Body = """
1. 本协议可能随功能及服务调整而更新。涉及数据用途、重要权利或使用条件的实质变化，应通过显著方式告知；依法需要重新取得同意的，应另行取得同意。
2. 您可以停止使用、删除角色或注销账号。涉及云端数据删除的后果，按本协议第五条执行。
3. 对违反本协议的使用行为，开发者可依据实际情况采取内容拦截、使用限制、暂停或终止服务等措施。
4. 停止提供服务，不改变开发者依法应承担的数据处理及其他义务。
""".trim()

internal val sec10Title = "十、联系与反馈"
internal val sec10Body = """
星识由个人开发者维护。您可以通过应用或项目页面公布的联系方式，反馈功能问题、输入误判、数据处理问题及其他意见。

请勿在公开反馈中提交API Key、密码、身份证件或未经处理的私人聊天记录。需要提供材料时，请仅提供定位问题所必要的内容。
""".trim()

@Composable
fun AgreementScreen(
    onAgree: () -> Unit,
    onDisagree: () -> Unit
) {
    var agreedToTerms by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFF5F5F5),
                        Color(0xFFEEEEEE),
                        Color(0xFFF5F5F5)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = themeText("agreement_title"),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = themeText("agreement_read"),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(16.dp)
            ) {
                Column {
                    AgreementIntro(agreementPreface)
                    AgreementSection(sec1Title, sec1Body)
                    AgreementSection(sec2Title, sec2Body)
                    AgreementSection(sec3Title, sec3Body)
                    AgreementSection(sec4Title, sec4Body)
                    AgreementSection(sec5Title, sec5Body)
                    AgreementSection(sec6Title, sec6Body)
                    AgreementSection(sec7Title, sec7Body)
                    AgreementSection(sec8Title, sec8Body)
                    AgreementSection(sec9Title, sec9Body)
                    AgreementSection(sec10Title, sec10Body)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = agreedToTerms,
                    onCheckedChange = { agreedToTerms = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = themeText("agreement_age"),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (agreedToTerms) FontWeight.Medium else FontWeight.Normal,
                        fontSize = 14.sp
                    ),
                    color = if (agreedToTerms) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onDisagree,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.Transparent)
                            .padding(1.dp)
                            .clip(RoundedCornerShape(23.dp))
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                themeText("agreement_disagree"),
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                Button(
                    onClick = { if (agreedToTerms) onAgree() },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    enabled = agreedToTerms,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (agreedToTerms)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        contentColor = Color.White,
                        disabledContainerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        disabledContentColor = Color.White
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.Transparent)
                            .padding(1.dp)
                            .clip(RoundedCornerShape(23.dp))
                            .background(
                                if (agreedToTerms)
                                    Brush.horizontalGradient(
                                        colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f), MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                                    )
                                else
                                    Brush.horizontalGradient(
                                        colors = listOf(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f), MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                                    )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                themeText("agreement_agree"),
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = themeText("version_info"),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
internal fun AgreementSection(title: String, content: String) {
    Column(modifier = Modifier.padding(bottom = 14.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = content,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.sp,
                lineHeight = 17.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
internal fun AgreementIntro(content: String) {
    Text(
        text = content,
        style = MaterialTheme.typography.bodySmall.copy(
            fontSize = 11.sp,
            lineHeight = 17.sp
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    )
    Spacer(modifier = Modifier.height(12.dp))
}
