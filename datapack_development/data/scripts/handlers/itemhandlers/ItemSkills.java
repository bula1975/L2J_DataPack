/*
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package handlers.itemhandlers;

import com.l2jserver.Config;
import com.l2jserver.gameserver.handler.IItemHandler;
import com.l2jserver.gameserver.model.L2ItemInstance;
import com.l2jserver.gameserver.model.L2Skill;
import com.l2jserver.gameserver.model.L2Object.InstanceType;
import com.l2jserver.gameserver.model.actor.L2Playable;
import com.l2jserver.gameserver.model.actor.instance.L2PcInstance;
import com.l2jserver.gameserver.model.actor.instance.L2PetInstance;
import com.l2jserver.gameserver.model.actor.instance.L2SummonInstance;
import com.l2jserver.gameserver.model.actor.instance.L2PcInstance.TimeStamp;
import com.l2jserver.gameserver.model.entity.TvTEvent;
import com.l2jserver.gameserver.network.SystemMessageId;
import com.l2jserver.gameserver.network.serverpackets.ActionFailed;
import com.l2jserver.gameserver.network.serverpackets.ExUseSharedGroupItem;
import com.l2jserver.gameserver.network.serverpackets.SystemMessage;
import com.l2jserver.gameserver.skills.SkillHolder;
import com.l2jserver.gameserver.templates.item.L2EtcItemType;

import javolution.util.FastMap;


public class ItemSkills implements IItemHandler
{
	/**
	 * 
	 * @see com.l2jserver.gameserver.handler.IItemHandler#useItem(com.l2jserver.gameserver.model.actor.L2Playable, com.l2jserver.gameserver.model.L2ItemInstance)
	 */
	public void useItem(L2Playable playable, L2ItemInstance item)
	{
		L2PcInstance activeChar;
		boolean isPet = playable instanceof L2PetInstance;
		if (isPet)
			activeChar = ((L2PetInstance) playable).getOwner();
		else if (playable instanceof L2PcInstance)
			activeChar = (L2PcInstance) playable;
		else
			return;

		if (activeChar.isInOlympiadMode())
		{
			activeChar.sendPacket(new SystemMessage(SystemMessageId.THIS_ITEM_IS_NOT_AVAILABLE_FOR_THE_OLYMPIAD_EVENT));
			return;
		}

		if (!TvTEvent.onScrollUse(playable.getObjectId()))
		{
			playable.sendPacket(ActionFailed.STATIC_PACKET);
			return;
		}

		// pets can use items only when they are tradeable
		if (isPet && !item.isTradeable())
		{
			activeChar.sendPacket(new SystemMessage(SystemMessageId.ITEM_NOT_FOR_PETS));
			return;
		}

		if (item.getItemId() == 726 || item.getItemId() == 728)
		{
			if (!Config.L2JMOD_ENABLE_MANA_POTIONS_SUPPORT)
			{
				activeChar.sendPacket(new SystemMessage(SystemMessageId.NOTHING_HAPPENED));
				return;
			}
		}

		int skillId;
		int skillLvl;
		
		final SkillHolder[] skills = item.getEtcItem().getSkills();
		if (skills != null)
		{
			for (SkillHolder skillInfo : skills)
			{
				if(skillInfo == null)
					continue;
				
				skillId = skillInfo.getSkillId();
				skillLvl = skillInfo.getSkillLvl();
				L2Skill itemSkill = skillInfo.getSkill();
				
				if (itemSkill != null)
				{
					if (!itemSkill.checkCondition(playable, playable.getTarget(), false))
						return;
					
					if (playable.isSkillDisabled(itemSkill))
					{
						reuse(activeChar,itemSkill, item);
						return ;
					}
					
					if (!itemSkill.isPotion() && playable.isCastingNow())
					{
						return;
					}
					
					if (itemSkill.getItemConsumeId() == 0 && itemSkill.getItemConsume() > 0)
					{
						if (!playable.destroyItem("Consume", item.getObjectId(), itemSkill.getItemConsume(), null, false))
						{
							activeChar.sendPacket(new SystemMessage(SystemMessageId.NOT_ENOUGH_ITEMS));
							return;
						}
					}
					
					// send message to owner
					if (isPet)
					{
						SystemMessage sm = new SystemMessage(SystemMessageId.PET_USES_S1);
						sm.addString(itemSkill.getName());
						activeChar.sendPacket(sm);
					}
					else
					{
						switch (skillId)
						{
						// short buff icon for healing potions
						case 2031:
						case 2032:
						case 2037:
						case 26025:
						case 26026:
							int buffId = activeChar._shortBuffTaskSkillId;
							// greater healing potions
							if (skillId == 2037 || skillId == 26025)
								activeChar.shortBuffStatusUpdate(skillId, skillLvl, itemSkill.getBuffDuration()/1000);
							// healing potions
							else if ((skillId == 2032 || skillId == 26026) && buffId !=2037 && buffId != 26025)
								activeChar.shortBuffStatusUpdate(skillId, skillLvl, itemSkill.getBuffDuration()/1000);
							// lesser healing potions
							else
							{
								if (buffId != 2037 && buffId != 26025 && buffId != 2032 && buffId != 26026)
									activeChar.shortBuffStatusUpdate(skillId, skillLvl, itemSkill.getBuffDuration()/1000);
							}
							break;
						}
					}

					if (itemSkill.isPotion())
					{
						playable.doSimultaneousCast(itemSkill);
						// Summons should be affected by herbs too, self time effect is handled at L2Effect constructor
						if (!isPet
								&& item.getItemType() == L2EtcItemType.HERB
								&& activeChar.getPet() != null
								&& activeChar.getPet() instanceof L2SummonInstance)
							activeChar.getPet().doSimultaneousCast(itemSkill);
					}
					else
					{
						playable.stopMove(null);
						playable.doCast(itemSkill);
					}

					if (itemSkill.getReuseDelay() > 0)
					{
						activeChar.addTimeStamp(itemSkill, itemSkill.getReuseDelay());
						activeChar.disableSkill(itemSkill, itemSkill.getReuseDelay());
						if (item.isEtcItem())
						{
							final int group = item.getEtcItem().getSharedReuseGroup();
							if (group >= 0)
								activeChar.sendPacket(new ExUseSharedGroupItem(item.getItemId(),
										group,
										itemSkill.getReuseDelay(),
										itemSkill.getReuseDelay()));
						}
					}
				}
			}
		}
	}
	
	private void reuse(L2PcInstance player,L2Skill skill, L2ItemInstance item)
	{
		SystemMessage sm = null;
		final FastMap<Integer, TimeStamp> timeStamp = player.getReuseTimeStamp();

		if (timeStamp != null && timeStamp.containsKey(skill.getReuseHashCode()))
		{
			final long remainingTime = player.getReuseTimeStamp().get(skill.getReuseHashCode()).getRemaining();
			final int hours = (int)(remainingTime / 3600000L);
			final int minutes = (int)(remainingTime % 3600000L) / 60000;
			final int seconds = (int)(remainingTime / 1000 % 60);
			if (hours > 0)
			{
				sm = new SystemMessage(SystemMessageId.S2_HOURS_S3_MINUTES_S4_SECONDS_REMAINING_FOR_REUSE_S1);
				if (skill.isPotion())
					sm.addItemName(item);
				else
					sm.addSkillName(skill);
				sm.addNumber(hours);
				sm.addNumber(minutes);
			}
			else if (minutes > 0)
			{
				sm = new SystemMessage(SystemMessageId.S2_MINUTES_S3_SECONDS_REMAINING_FOR_REUSE_S1);
				if (skill.isPotion())
					sm.addItemName(item);
				else
					sm.addSkillName(skill);
				sm.addNumber(minutes);
			}
			else
			{
				sm = new SystemMessage(SystemMessageId.S2_SECONDS_REMAINING_FOR_REUSE_S1);
				if (skill.isPotion())
					sm.addItemName(item);
				else
					sm.addSkillName(skill);
			}
			sm.addNumber(seconds);

			if (item.isEtcItem())
			{
				final int group = item.getEtcItem().getSharedReuseGroup();
				if (group >= 0)
					player.sendPacket(new ExUseSharedGroupItem(item.getItemId(),
							group,
							(int)remainingTime,
							skill.getReuseDelay()));
			}
		}
		else
		{
			sm = new SystemMessage(SystemMessageId.S1_PREPARED_FOR_REUSE);
			sm.addItemName(item);
		}
		player.sendPacket(sm);
	}
}
